package k.frontend

import org.antlr.runtime.tree.ParseTree
import k.frontend
import java.io._
import java.nio
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.Path
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import org.json.JSONArray
import org.json.JSONObject
import k.frontend.ModelParser.ModelContext
import org.json.JSONTokener
import scala.collection.mutable.{ ListBuffer => MList }

object Frontend {
  type OptionMap = Map[Symbol, Any]

  def log(msg: String = "") = Misc.log("main", msg)

  def parseArgs(map: OptionMap, list: List[String]): OptionMap = {
    def isSwitch(s: String) = (s(0) == '-')
    list match {
      case Nil => map
      case "-f" :: value :: tail =>
        parseArgs(map ++ Map('modelFile -> value), tail)
      case "-tests" :: tail    => parseArgs(map ++ Map('tests -> true), tail)
      case "-baseline" :: tail => parseArgs(map ++ Map('baseline -> true), tail)
      case "-test" :: tail     => parseArgs(map ++ Map('test -> true), tail)
      case "-v" :: tail        => parseArgs(map ++ Map('verbose -> true), tail)
      case "-stats" :: tail    => parseArgs(map ++ Map('stats -> true), tail)
      case "-dot" :: tail      => parseArgs(map ++ Map('dot -> true), tail)
      case "-json" :: tail     => parseArgs(map ++ Map('printJson -> true), tail)
      case "-mmsJson" :: value :: tail =>
        parseArgs(map ++ Map('mmsJson -> value), tail)
      case "-expressionToJson" :: value :: tail =>
        parseArgs(map ++ Map('expression -> value), tail)
      case "-jsonToExpression" :: value :: tail =>
        parseArgs(map ++ Map('json -> value), tail)
      case option :: tail =>
        println("Unknown option " + option)
        System.exit(1).asInstanceOf[Nothing]
    }
  }

  def scala_main(args: Array[String]) {
    val options = parseArgs(Map(), args.toList)
    var model: Model = null
    var filename: String = null

    options.get('tests) match {
      case Some(true) => doTests(options.getOrElse('baseline, false).asInstanceOf[Boolean])
      case _          => ()
    }

    options.get('test) match {
      case Some(_) =>
        log("Please enter the test case to run:")
        val testCase = readLine.trim
        val fileName = testCase.asInstanceOf[String]
        val testsDir = new File(new File(new File(".").getAbsolutePath, "src"), "tests")
        val file = new File(testsDir, fileName)
        val result = doTest(file)
        val baselineFile = new File(testsDir, "baseline.json")
        val baselineObject =
          if (baselineFile.exists) {
            val json = scala.io.Source.fromFile(baselineFile).mkString
            var tokener: JSONTokener = new JSONTokener(json)
            var jsonObject: JSONObject = new JSONObject(tokener)
            jsonObject
          } else {
            new JSONObject()
          }
        if (baselineObject.has(fileName))
          compareSingleResultDetail(baselineObject.getJSONObject(fileName), result, testsDir)
        else log(s"Baseline does not contain $fileName. Cannot compare.")
      case _ => ()
    }

    options.get('modelFile) match {
      case Some(f: String) =>
        model = getModelFromFile(f)
        filename = Paths.get(f).getFileName.toString
      case _ => ()
    }

    options.get('mmsJson) match {
      case Some(file: String) => {
        model = parseMMSJson(file)
      }
      case _ => ()
    }

    if (model != null) {
      try {
        val tc: TypeChecker = new TypeChecker(model)
        tc.smtCheck
        log("Type checking completed. No errors found.")
      } catch {
        case TypeCheckException => Misc.errorExit("Main", "Given K did not type check.")
        case e: Throwable =>
          e.printStackTrace()
          Misc.errorExit("Main", "Exception encountered during type checking.")
      }
    }

    options.get('printJson) match {
      case Some(_) =>
        if (model != null) {
          // Remember old value of option
          val optionsUseJson1 = ASTOptions.useJson1
          // MMS method using toJson1
          ASTOptions.useJson1 = true
          println("JSON1: " + model.toJson)
          val modelFromJson = visitJsonObject(model.toJson).asInstanceOf[Model]
          // MMS method using toJson2
          ASTOptions.useJson1 = false
          println("JSON2: " + model.toJson)
          val modelFromJson2 = visitJsonObject2(model.toJson).asInstanceOf[Model]
          // Reset old value of option
          ASTOptions.useJson1 = optionsUseJson1
        } else
          println("Model was null!")
      case _ => ()
    }

//    if (model != null) {
//      val smtModel = model.toSMT
//      if (K2Z3.debug) {
//        println("--- SMT Model ---")
//        println(smtModel)
//        println("-----------------")
//      }
//      K2Z3.solveSMT(model, smtModel, true)
//    }

    // print DOT format class diagram
    options.get('dot) match {
      case Some(_) => if (model != null) printClassDOT(filename, model)
      case _       => ()
    }

    options.get('stats) match {
      case Some(_) => printStats(model)
      case _       => ()
    }

    options.get('expression) match {
      case Some(expressionString: String) => {
        println(exp2Json(expressionString))
      }
      case _ => ()
    }

  }

  def getFileTree(f: File): Stream[File] =
    f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
    else Stream.empty)

  def doTest(file: File): JSONObject = {
    log(s"Running test ${file.getName}")
    TypeChecker.reset
    UtilSMT.reset
    K2Z3.debug = false
    K2Z3.silent = true
    ASTOptions.debug = false
    ASTOptions.silent = true
    TypeChecker.silent = true
    TypeChecker.debug = false

    val model = getModelFromFile(file.toString)

    if (model != null) new TypeChecker(model).smtCheck

    val (json1, json2) =
      if (model != null) {
        ASTOptions.useJson1 = true
        val json1 = model.toJson
        ASTOptions.useJson1 = false
        val json2 = model.toJson
        (json1, json2)
      } else (null, null)
    val smt =
      if (model != null) model.toSMT
      else null
    val smtModel =
      if (smt != null) {
        import scala.actors.Futures._
        def runWithTimeout[T](timeoutMs: Long)(f: => T): Option[T] = {
          awaitAll(timeoutMs, future(f)).head.asInstanceOf[Option[T]]
        }
        val res = runWithTimeout(10000) { K2Z3.solveSMT(model, smt, false) }
        if (res.isEmpty) null
        else K2Z3.z3Model.toString
      } else null

    val currentTestJsonObject = new JSONObject()

    currentTestJsonObject.put("name", file.getName)
    currentTestJsonObject.put("model", model.toString)
    currentTestJsonObject.put("json1", json1)
    currentTestJsonObject.put("json2", json2)
    currentTestJsonObject.put("smt", smt)
    currentTestJsonObject.put("smtModel", smtModel)

  }

  def doTests(saveBaseline: Boolean) {

    var resultRows: List[List[String]] = List(List("Name", "ModelEqual", "JSON1Equal", "JSON2Equal", "SMTEqual", "SMTModelEqual"))
    val testsDir = new File(new File(new File(".").getAbsolutePath, "src"), "tests")
    var kFiles = getFileTree(testsDir).filter(_.getName.endsWith(".k"))
    val baselineFile = new File(testsDir, "baseline.json")
    val baselineObject =
      if (baselineFile.exists) {
        val json = scala.io.Source.fromFile(baselineFile).mkString
        var tokener: JSONTokener = new JSONTokener(json)
        var jsonObject: JSONObject = new JSONObject(tokener)
        jsonObject
      } else {
        new JSONObject()
      }
    val currentResultsObject = new JSONObject()
    var testsRun: Int = 0
    var testsMatched: Int = 0

    kFiles.foreach { file =>
      try {
        testsRun = testsRun + 1
        val currentTestJsonObject = doTest(file)

        currentResultsObject.put(file.getName, currentTestJsonObject)

        if (baselineObject.has(file.getName)) {
          val result = compareResult(baselineObject.getJSONObject(file.getName), currentTestJsonObject)
          resultRows = result._2 :: resultRows
          if (result._1) testsMatched = testsMatched + 1
        } else {
          resultRows = List(file.getName + "*", "New", "test", "case", "", "") :: resultRows
        }

      } catch {
        case TypeCheckException => resultRows = List(file.getName + "*", "Does", "not", "type", "check", "") :: resultRows
        case K2SMTException     => resultRows = List(file.getName + "*", "K2SMT", "error", "", "", "") :: resultRows
        case K2Z3Exception      => resultRows = List(file.getName + "*", "K2Z3", "error", "", "", "") :: resultRows
        case _: Throwable       => resultRows = List(file.getName + "*", "-", "-", "-", "-", "-") :: resultRows
      }
    }
    if (saveBaseline) {
      val fw = new FileWriter(baselineFile)
      fw.write(currentResultsObject.toString)
      fw.close
      println("Baseline saved.")
    }
    println
    println("Results:")
    println
    println(Tabulator.format(resultRows.reverse))
    println
    println(s"\t$testsMatched/$testsRun tests matched the stored baseline.")
  }

  def compareSingleResultDetail(bo: JSONObject, co: JSONObject, testDir: File) {
    var resultRows: List[List[String]] = List(List("Name", "ModelEqual", "JSON1Equal", "JSON2Equal", "SMTEqual", "SMTModelEqual"))
    log()
    println(Tabulator.format((compareResult(bo, co)._2 :: resultRows).reverse))
    log()
    var fw = new FileWriter(new File(testDir, "baseline.smt"))
    fw.write(bo.getString("smt"))
    fw.close
    log(s"Baseline SMT stored in ${testDir.getAbsolutePath}/baseline.smt")
    fw = new FileWriter(new File(testDir, "current.smt"))
    fw.write(co.getString("smt"))
    fw.close
    log(s"Current SMT stored in ${testDir.getAbsolutePath}/current.smt")

    fw = new FileWriter(new File(testDir, "baseline.smt.model"))
    fw.write(bo.getString("smtModel"))
    fw.close
    log(s"Baseline SMT model stored in ${testDir.getAbsolutePath}/baseline.smt.model")
    fw = new FileWriter(new File(testDir, "current.smt.model"))
    fw.write(co.getString("smtModel"))
    fw.close
    log(s"Current SMT model stored in ${testDir.getAbsolutePath}/current.smt.model")

  }

  def compareSingleResult(key: String, bo: JSONObject, co: JSONObject): String = {
    if (bo.has(key) && co.has(key)) {

      if (bo.get(key).isInstanceOf[JSONObject] && co.get(key).isInstanceOf[JSONObject])
        bo.getJSONObject(key).similar(co.getJSONObject(key)).toString
      else bo.get(key).toString.equals(co.get(key).toString).toString
    } else if (bo.has(key) && !co.has(key))
      "false"
    else if (co.has(key) && !bo.has(key))
      "Missing in baseline"
    else "-"
  }

  def compareResult(bo: JSONObject, co: JSONObject): (Boolean, List[String]) = {
    val modelEq = compareSingleResult("model", bo, co)
    val json1Eq = compareSingleResult("json1", bo, co)
    val json2Eq = compareSingleResult("json2", bo, co)
    val smtEq = compareSingleResult("smt", bo, co)
    val smtModelEq = compareSingleResult("smtModel", bo, co)
    if (modelEq != "true" || json1Eq != "true" ||
      json2Eq != "true" || smtEq != "true" || smtModelEq != "true")
      (false, List(bo.getString("name") + "*", s"$modelEq", s"$json1Eq",
        s"$json2Eq", s"$smtEq", s"$smtModelEq"))
    else
      (true, List(bo.getString("name"), s"$modelEq", s"$json1Eq",
        s"$json2Eq", s"$smtEq", s"$smtModelEq"))
  }

  def parseMMSJson(file: String): Model = {
    val json = scala.io.Source.fromFile(file).mkString
    var tokener: JSONTokener = new JSONTokener(json)
    var jsonObject: JSONObject = new JSONObject(tokener)
    val elementsArray = jsonObject.get("elements").asInstanceOf[JSONArray]
    var packageName: Option[PackageDecl] = None
    var imports: List[ImportDecl] = List()
    var annotations: List[AnnotationDecl] = List()
    var mdecls: List[TopDecl] = List[TopDecl]()
    var id2Decl: Map[String, TopDecl] = Map()

    // first build the classes 
    for (i <- Range(0, elementsArray.length())) {
      val obj = elementsArray.get(i).asInstanceOf[JSONObject]
      if (obj.keySet.contains("specialization")) {
        if (obj.getString("name").length == 0) {
          println("Warning: found unnamed element in JSON: " + obj.getString("sysmlid"))
        } else {
          val specializationObject = obj.getJSONObject("specialization")
          specializationObject.getString("type") match {
            case "Element" =>
              val entity = EntityDecl(Nil, ClassToken, None, obj.getString("name"), Nil, Nil, Nil)
              mdecls = entity :: mdecls
              id2Decl += (obj.getString("sysmlid") -> entity)
            case _ => ()
          }
        }
      }
    }

    // now we can process properties and constraints

    for (i <- Range(0, elementsArray.length())) {
      val obj = elementsArray.get(i).asInstanceOf[JSONObject]
      if (obj.keySet.contains("specialization")) {
        val specializationObject = obj.getJSONObject("specialization")
        if (obj.getString("name").length == 0 &&
          specializationObject.getString("type") != "Generalization") {
          println("Warning: found unnamed element in JSON: " + obj.getString("sysmlid"))
        } else {
          specializationObject.getString("type") match {
            case "Property" =>
              val owningDecl = id2Decl(obj.getString("owner")).asInstanceOf[EntityDecl]
              val propertyType =
                if (specializationObject.get("propertyType") == JSONObject.NULL) IntType
                else {
                  val typeDecl = id2Decl(specializationObject.getString("propertyType")).asInstanceOf[EntityDecl]
                  IdentType(QualifiedName(List(typeDecl.ident)), List())
                }
              val property = PropertyDecl(Nil, obj.getString("name"), propertyType, None, None, None)
              val newDecl = EntityDecl(owningDecl.annotations, owningDecl.entityToken,
                owningDecl.keyword, owningDecl.ident,
                owningDecl.typeParams, owningDecl.extending,
                property :: owningDecl.members)
              mdecls = mdecls.diff(List(owningDecl))
              mdecls = newDecl :: mdecls

              id2Decl += (obj.getString("owner") -> newDecl)

            case "Package" => packageName =
              Some(PackageDecl(QualifiedName(obj.getString("qualifiedName").replace("-", "_").split("/").toList.filterNot { _.isEmpty })))
            case "Constraint" =>
              val constraintExpression = specializationObject.getJSONObject("specification").getJSONArray("expressionBody").get(0).asInstanceOf[String]
              val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(constraintExpression)
              var m: Model = ksv.visit(tree).asInstanceOf[Model]
              var exp: Exp = m.decls(0).asInstanceOf[ExpressionDecl].exp
              val owningDecl = id2Decl(obj.getString("owner")).asInstanceOf[EntityDecl]
              val constraint = ConstraintDecl(Some(obj.getString("name")), exp)
              val newDecl = EntityDecl(owningDecl.annotations, owningDecl.entityToken,
                owningDecl.keyword, owningDecl.ident,
                owningDecl.typeParams, owningDecl.extending,
                constraint :: owningDecl.members)
              mdecls = mdecls.diff(List(owningDecl))
              mdecls = newDecl :: mdecls
              id2Decl += (obj.getString("owner") -> newDecl)
            case "Generalization" =>
              val owningDecl = id2Decl(specializationObject.getString("source")).asInstanceOf[EntityDecl]
              val targetDecl = id2Decl(specializationObject.getString("target")).asInstanceOf[EntityDecl]
              val newDecl = EntityDecl(owningDecl.annotations, owningDecl.entityToken,
                owningDecl.keyword, owningDecl.ident,
                owningDecl.typeParams, IdentType(QualifiedName(List(targetDecl.ident)), List()) :: owningDecl.extending, owningDecl.members)
              mdecls = mdecls.diff(List(owningDecl))
              mdecls = newDecl :: mdecls
              id2Decl += (obj.getString("owner") -> newDecl)

            case _ => ()
          }
        }
      }
    }

    val model = Model(packageName, imports, annotations, mdecls)
    println(model)
    model
  }

  def printClassDOT(filename: String, model: Model) = {
    val classFile = new FileWriter(filename + ".dot", false)
    classFile.append("digraph G { node [shape=record,fontname=Courier,fontsize=10,color=\".7 .3 1.0\"];")
    model.decls.foreach { d =>
      if (d.isInstanceOf[EntityDecl]) {
        val ed = d.asInstanceOf[EntityDecl]
        val properties =
          ed.members.filter { m => m.isInstanceOf[PropertyDecl] }
            .map(m => m.asInstanceOf[PropertyDecl].name).asInstanceOf[List[String]].mkString("|")
        val functions =
          ed.members.filter { m => m.isInstanceOf[FunDecl] }
            .map(m => m.asInstanceOf[FunDecl].ident).asInstanceOf[List[String]].mkString("|")
        val label = s"${ed.ident} | {Properties | $properties} | {Functions | $functions}"
        classFile.append(ed.ident + " [shape=record,label=\"" + label + "\"];\r\n")
        ed.members.foreach { m =>
          if (m.isInstanceOf[PropertyDecl]) {
            classFile.append(s"${ed.ident} -> ${
              m.asInstanceOf[PropertyDecl].ty.toString
                .replace("[", "")
                .replace("]", "")
                .replace("Set", "")
            };")
          }
        }
      }
    }
    classFile.append("}")
    classFile.close()
  }

  def visitJsonObject(o: Any): AnyRef = {
    val obj = o.asInstanceOf[JSONObject]
    obj.getString("type") match {
      // Expressions:
      case "ParenExp" =>
        ParenExp(visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "IdentExp" =>
        IdentExp(obj.get("ident").asInstanceOf[String])
      case "DotExp" =>
        val exp: Exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        val ident: String = obj.get("ident").asInstanceOf[String]
        DotExp(exp, ident)
      case "IfExp" =>
        val cond: Exp = visitJsonObject(obj.get("cond")).asInstanceOf[Exp]
        val trueBranch: Exp = visitJsonObject(obj.get("trueBranch")).asInstanceOf[Exp]
        val falseBranch: Option[Exp] =
          if (obj.keySet().contains("falseBranch")) Some(visitJsonObject(obj.get("falseBranch")).asInstanceOf[Exp])
          else None
        IfExp(cond, trueBranch, falseBranch)
      case "WhileExp" =>
        val cond: Exp = visitJsonObject(obj.get("condition")).asInstanceOf[Exp]
        val body = visitJsonObject(obj.get("body")).asInstanceOf[Exp]
        WhileExp(cond, body)
      case "ForExp" =>
        val pattern: Pattern = visitJsonObject(obj.get("pattern")).asInstanceOf[Pattern]
        val exp: Exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        val body = visitJsonObject(obj.get("body")).asInstanceOf[Exp]
        ForExp(pattern, exp, body)
      case "FunApplExp" =>
        val exp1: Exp = visitJsonObject(obj.get("exp1")).asInstanceOf[Exp]
        val args: List[Argument] = visitJsonArray(obj.get("args"), visitJsonObject).asInstanceOf[List[Argument]]
        FunApplExp(exp1, args)
      case "BinExp" =>
        val operator: BinaryOp =
          obj.get("op") match {
            case "Plus"    => ADD
            case "Minus"   => SUB
            case "Times"   => MUL
            case "Divide"  => DIV
            case "Modulo"  => REM
            case "LTE"     => LTE
            case "GTE"     => GTE
            case "LT"      => LT
            case "GT"      => GT
            case "EQ"      => EQ
            case "NotEQ"   => NEQ
            case "IsIn"    => ISIN
            case "NotIn"   => NOTISIN
            case "Subset"  => SUBSET
            case "Psubset" => PSUBSET
            case "Union"   => SETUNION
            case "Inter"   => SETINTER
            case "And"     => AND
            case "OR"      => OR
            case "Tuples"  => TUPLEINDEX
            case "Concat"  => LISTCONCAT
            case "Implies" => IMPL
            case "Assign"  => ASSIGN
            case x =>
              println(x + " not recognized as a BinOp.")
              null
          }
        BinExp(visitJsonObject(obj.get("exp1")).asInstanceOf[Exp],
          operator,
          visitJsonObject(obj.get("exp2")).asInstanceOf[Exp])
      case "UnaryExp" =>
        val operator: UnaryOp =
          obj.get("op") match {
            case "Neg"  => NEG
            case "Not"  => NOT
            case "Prev" => PREV
          }
        UnaryExp(operator, visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "QuantifiedExp" =>
        val quant = visitJsonObject(obj.get("quant")).asInstanceOf[Quantifier]
        val bindings = visitJsonArray(obj.get("bindings"), visitJsonObject).asInstanceOf[List[RngBinding]]
        val exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        QuantifiedExp(quant, bindings, exp)
      case "TupleExp" =>
        TupleExp(visitJsonArray(obj.get("exps"), visitJsonObject).asInstanceOf[List[Exp]])
      case "CollectionEnumExp" =>
        CollectionEnumExp(getCollectionKind(obj.getString("kind")),
          visitJsonArray(obj.get("exps"), visitJsonObject).asInstanceOf[List[Exp]])
      case "CollectionRangeExp" =>
        CollectionRangeExp(getCollectionKind(obj.getString("kind")),
          visitJsonObject(obj.get("exp1")).asInstanceOf[Exp],
          visitJsonObject(obj.get("exp2")).asInstanceOf[Exp])
      case "CollectionComprExp" =>
        var kind = getCollectionKind(obj.getString("kind"))
        var exp1 = visitJsonObject(obj.get("exp1")).asInstanceOf[Exp]
        var bindings = visitJsonArray(obj.get("bindings"), visitJsonObject).asInstanceOf[List[RngBinding]]
        var exp2 = visitJsonObject(obj.get("exp2")).asInstanceOf[Exp]
        CollectionComprExp(kind, exp1, bindings, exp2)
      case "LambdaExp" =>
        var pat = visitJsonObject(obj.get("pat")).asInstanceOf[Pattern]
        var exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        LambdaExp(pat, exp)
      case "AssertExp" =>
        AssertExp(visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "StarExp" =>
        StarExp
      case "ResultExp" =>
        ResultExp
      case "QualifiedName" =>
        QualifiedName(visitJsonArray(obj.get("names").asInstanceOf[JSONArray], (x => x.asInstanceOf[String])).asInstanceOf[List[String]])
      case "IdentType" =>
        val ident = visitJsonObject(obj.get("ident")).asInstanceOf[QualifiedName]
        val args = visitJsonArray(obj.get("args").asInstanceOf[JSONArray], visitJsonObject).asInstanceOf[List[Type]]
        IdentType(ident, args)
      case "PositionalArgument" =>
        PositionalArgument(visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "NamedArgument" =>
        val ident: String = obj.getString("ident")
        val exp: Exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        NamedArgument(ident, exp)
      case "CartesianType" =>
        CartesianType(visitJsonArray(obj.get("types"), visitJsonObject).asInstanceOf[List[Type]])
      case "IdentPattern" =>
        IdentPattern(obj.get("ident").asInstanceOf[String])
      case "ProductPattern" =>
        ProductPattern(visitJsonArray(obj.get("patterns"), visitJsonObject).asInstanceOf[List[Pattern]])
      case "RngBinding" =>
        val patterns = visitJsonArray(obj.get("patterns"), visitJsonObject).asInstanceOf[List[Pattern]]
        val collection = visitJsonObject(obj.get("collection")).asInstanceOf[Collection]
        RngBinding(patterns, collection)
      case "ExpCollection" =>
        ExpCollection(visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "TypeCollection" =>
        TypeCollection(visitJsonObject(obj.get("ty")).asInstanceOf[Type])
      case "LiteralInteger" =>
        IntegerLiteral(obj.getInt("i"))
      case "LiteralFloatingPoint" =>
        //RealLiteral(java.lang.Float.parseFloat(obj.get("f").toString)) // was: asInstanceOf[String]
        val bd = new java.math.BigDecimal(obj.get("f").toString).setScale(8, java.math.BigDecimal.ROUND_UNNECESSARY)
        RealLiteral(bd)
      case "LiteralCharacter" =>
        CharacterLiteral(obj.get("c").asInstanceOf[Char])
      case "LiteralBoolean" =>
        BooleanLiteral(obj.getBoolean("b"))
      case "StringLiteral" =>
        StringLiteral(obj.getString("string"))
      case "ElementValue" =>
        IdentExp(obj.get("element").asInstanceOf[String])
      // Top level:
      case "Model" =>
        var packageName: Option[PackageDecl] =
          if (obj.keySet().contains("packageName")) Some(visitJsonObject(obj.get("packageName"))).asInstanceOf[Option[PackageDecl]]
          else None
        var annotations = visitJsonArray(obj.get("annotations"), visitJsonObject).asInstanceOf[List[AnnotationDecl]]
        var imports = visitJsonArray(obj.get("imports"), visitJsonObject).asInstanceOf[List[ImportDecl]]
        var decls = visitJsonArray(obj.get("decls"), visitJsonObject).asInstanceOf[List[TopDecl]]
        Model(packageName, imports, annotations, decls)
      case "AnnotationDecl" =>
        AnnotationDecl(obj.getString("name"), visitJsonObject(obj.get("ty")).asInstanceOf[Type])
      case "Annotation" =>
        Annotation(obj.getString("name"), visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "PackageDecl" =>
        var name: QualifiedName = visitJsonObject(obj.get("name")).asInstanceOf[QualifiedName]
        PackageDecl(name)
      case "ImportDecl" =>
        var name: QualifiedName = visitJsonObject(obj.get("name")).asInstanceOf[QualifiedName]
        var star: Boolean =
          if (obj.get("star").equals("true")) true
          else false
        ImportDecl(name, star)
      case "EntityDecl" =>
        var annotations = visitJsonArray(obj.get("annotations"), visitJsonObject).asInstanceOf[List[Annotation]]
        var entityToken =
          if (obj.get("entityToken").equals("class")) ClassToken
          else if (obj.get("entityToken").equals("assoc")) AssocToken
          else IdentifierToken(obj.getString("entityToken"))
        var keyword =
          if (obj.keySet().contains("keyword")) Some(obj.getString("keyword"))
          else None
        var ident: String = obj.get("ident").toString()
        var typeParams: List[TypeParam] =
          if (obj.keySet.contains("typeParams"))
            visitJsonArray(obj.get("typeParams"), visitJsonObject).asInstanceOf[List[TypeParam]]
          else Nil
        var extending: List[Type] =
          visitJsonArray(obj.get("extending"), visitJsonObject).asInstanceOf[List[Type]]
        var members: List[MemberDecl] =
          visitJsonArray(obj.get("members"), visitJsonObject).asInstanceOf[List[MemberDecl]]
        EntityDecl(annotations, entityToken, keyword, ident, typeParams, extending, members)
      case "TypeParam" =>
        var ident: String = obj.get("ident").toString()
        var bound: Option[TypeBound] =
          if (obj.keySet().contains("bound")) Some(visitJsonObject(obj.get("bound")).asInstanceOf[TypeBound])
          else None
        TypeParam(ident, bound)
      case "TypeBound" => // should this be here?
        visitJsonArray(obj.get("types"), visitJsonObject).asInstanceOf[List[Type]]
      case "TypeDecl" =>
        var ident: String = obj.get("ident").toString()
        var typeParams: List[TypeParam] = visitJsonArray(obj.get("typeParam"), visitJsonObject).asInstanceOf[List[TypeParam]]
        var ty =
          if (obj.keySet().contains("ty")) Some(visitJsonObject(obj.get("ty")).asInstanceOf[Type])
          else None
        TypeDecl(ident, typeParams, ty)
      case "PropertyDecl" =>
        var modifiers =
          visitJsonArray(obj.get("modifiers"), getModifier).asInstanceOf[List[PropertyModifier]]
        var name = obj.getString("name")
        var ty = visitJsonObject(obj.get("ty")).asInstanceOf[Type]
        var multiplicity =
          if (obj.keySet.contains("multiplicity")) Some(visitJsonObject(obj.get("multiplicity")).asInstanceOf[Multiplicity])
          else None
        var assignment =
          if (obj.keySet.contains("assignment")) Some(obj.getBoolean("assignment"))
          else None
        var expr: Option[Exp] =
          if (obj.keySet().contains("expr")) Some(visitJsonObject(obj.get("expr")).asInstanceOf[Exp])
          else None
        PropertyDecl(modifiers, name, ty, multiplicity, assignment, expr)
      case "FunDecl" =>
        var ident: String = obj.get("ident").toString()
        var typeParams = visitJsonArray(obj.get("typeParams"), visitJsonObject).asInstanceOf[List[TypeParam]]
        var params = visitJsonArray(obj.get("params"), visitJsonObject).asInstanceOf[List[Param]]
        var ty =
          if (obj.keySet().contains("ty")) Some(visitJsonObject(obj.get("ty")).asInstanceOf[Type])
          else None
        var spec = visitJsonArray(obj.get("spec"), visitJsonObject).asInstanceOf[List[FunSpec]]
        var body: List[MemberDecl] = visitJsonArray(obj.get("body"), visitJsonObject).asInstanceOf[List[MemberDecl]]
        FunDecl(ident, typeParams, params, ty, spec, body)
      case "FunSpec" =>
        FunSpec(obj.getBoolean("pre"), visitJsonObject(obj.get("exp")).asInstanceOf[Exp])
      case "ConstraintDecl" =>
        var name: Option[String] =
          if (obj.keySet().contains("name")) Some(obj.get("name").toString())
          else None
        var exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        ConstraintDecl(name, exp)
      case "ExpressionDecl" =>
        var exp = visitJsonObject(obj.get("exp")).asInstanceOf[Exp]
        ExpressionDecl(exp)
      case "Multiplicity" =>
        var exp1 = visitJsonObject(obj.get("exp1")).asInstanceOf[Exp]
        val exp2: Option[Exp] =
          if (obj.keySet().contains("falseBranch")) Some(visitJsonObject(obj.get("exp2")).asInstanceOf[Exp])
          else None
        Multiplicity(exp1, exp2)
      case "Param" =>
        Param(obj.getString("name"), visitJsonObject(obj.get("ty")).asInstanceOf[Type])
      case "Quantifier" =>
        obj.getString("element") match {
          case "Forall" => Forall
          case "Exists" => Exists
        }
      case "TypeCastCheckExp" =>
        TypeCastCheckExp(obj.getBoolean("cast"), visitJsonObject(obj.get("exp")).asInstanceOf[Exp], visitJsonObject(obj.get("ty")).asInstanceOf[Type])
      case "BlockExp" =>
        BlockExp(visitJsonArray(obj.getJSONArray("body"), visitJsonObject).asInstanceOf[List[MemberDecl]])
      case "BoolType"   => BoolType
      case "IntType"    => IntType
      case "RealType"   => RealType
      case "StringType" => StringType
      case "UnitType"   => UnitType
      case "CharType"   => CharType
      case key @ _ =>
        println("Unknown keys encountered in JSON string!: " + key)
        System.exit(-1).asInstanceOf[Nothing]
    }
  }

  def visitJsonArray(o: Any, f: Any => AnyRef): List[AnyRef] = {
    val obj = o.asInstanceOf[JSONArray]
    var res: List[AnyRef] = Nil
    for (i <- Range(0, obj.length())) {
      val element = obj.get(i)
      res = res ++ List(f(element))
    }
    res
  }

  def getModifier(po: Any): AnyRef = {
    po.asInstanceOf[String] match {
      case "part"    => Part
      case "var"     => Var
      case "val"     => Val
      case "source"  => Source
      case "target"  => Target
      case "unique"  => Unique
      case "ordered" => Ordered
    }
  }

  // Assuming that the input to this is an expression in JSON string format
  def json2exp(expressionString: String): String = {
    var tokener: JSONTokener = new JSONTokener(expressionString)
    var jsonObject: JSONObject = new JSONObject(tokener)
    //var element: JSONArray = jsonObject.get("elements").asInstanceOf[JSONArray]
    //var specialization: JSONObject = element.get(0).asInstanceOf[JSONObject]
    //var exp: Exp = visitJsonObject(specialization.get("specialization").asInstanceOf[JSONObject]).asInstanceOf[Exp]
    var exp = visitJsonObject(jsonObject)
    exp.toString()
  }

  def getRngBindingList(o: JSONObject): List[RngBinding] = {
    visitJsonArray(o.get("bindings").asInstanceOf[JSONArray], getRngBinding).asInstanceOf[List[RngBinding]]
  }

  def getPattern(po: Any): AnyRef = {
    val p = po.asInstanceOf[JSONObject]
    val operand = p.get("operand").asInstanceOf[JSONArray]
    operand.get(0).asInstanceOf[JSONObject].get("type") match {
      case "IdentPattern"   => IdentPattern(operand.getString(1))
      case "ProductPattern" => ProductPattern(getPatternList(p.get("operand").asInstanceOf[JSONArray].get(1).asInstanceOf[JSONArray]))
    }
  }

  def getPatternList(pl: JSONArray): List[Pattern] = {
    visitJsonArray(pl, getPattern).asInstanceOf[List[Pattern]]
  }

  def getCollection(o: JSONObject): Collection = {
    val operand = o.getJSONArray("operand")
    operand.getJSONObject(0).get("type") match {
      case "ExpCollection" =>
        ExpCollection(visitJsonObject2(operand.get(1)).asInstanceOf[Exp])
      case "TypeCollection" =>
        TypeCollection(visitJsonObject2(operand.get(1)).asInstanceOf[Type])
    }
  }

  def getCollectionKind(o: String): CollectionKind = {
    o match {
      case "Set" => SetKind
      case "Seq" => SeqKind
      case "Bag" => BagKind
    }
  }

  def getRngBinding(o: Any): AnyRef = {
    val obj = o.asInstanceOf[JSONObject].getJSONArray("operand")
    val patternList: MList[Pattern] = MList()
    for (i <- Range(2, obj.length)) {
      patternList += getPattern(obj.getJSONObject(i)).asInstanceOf[Pattern]
    }
    val collection = getCollection(obj.get(1).asInstanceOf[JSONObject])
    RngBinding(patternList.toList, collection)
  }

  
  
  def visitJsonObject2(o: Any): AnyRef = {
    visitJsonObject2(o, false)
  }
  
  def visitJsonObject2(o: Any, pullOperandType: Boolean): AnyRef = {
    val obj = o.asInstanceOf[JSONObject]
    var spec = obj.optJSONObject("specialization")
    if ( spec != null ) {
      return visitJsonObject2(spec, pullOperandType)
    }
    // i is the index to the first argument
    val i = if (pullOperandType) 1 else 2
    val operand: JSONArray = if (!pullOperandType) null else obj.get("operand").asInstanceOf[JSONArray]
    val theType = if (!pullOperandType) obj.getString("type") 
          else {
            operand.getJSONObject(0).getString("element")
          }
    val opIsElementValue = 
      if (!pullOperandType) false 
      else {
        var kind = operand.get(0).asInstanceOf[JSONObject].get("type").asInstanceOf[String]
        kind match { 
          case "ElementValue" => true
          case _ => false
        }
      }
    theType match {
//      case "ElementValue" =>
//        visitJsonObject2(o, true)
      case "Expression" =>
        visitJsonObject2(o, true)
//        val operand: JSONArray = obj.get("operand").asInstanceOf[JSONArray]
//        var kind = operand.get(0).asInstanceOf[JSONObject].get("type").asInstanceOf[String]
//        var opIsElementValue = kind match { 
//          case "ElementValue" => true
//          case _ => false
//        }
//        else {
//        kind = if (opIsElementValue) operand.getJSONObject(0).getString("element")
//               else kind
//        kind match {
          case "BlockExp" =>
            val memberDecls: MList[MemberDecl] = MList()
            for (j <- Range(i, operand.length())) {
              memberDecls += visitJsonObject2(operand.getJSONObject(j)).asInstanceOf[MemberDecl]
            }
            BlockExp(memberDecls.toList)
          case "ParenExp" =>
            ParenExp(visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp])
          case "DotExp" =>
            val exp = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val ident = operand.getString(i+1)
            DotExp(exp, ident)
          case "StarExp" | "Star" => StarExp
          case "FunApplExp" =>
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val args =
              if (operand.length > 2) {
                val argsList: MList[Argument] = MList()
                for (j <- Range(i+1, operand.length())) {
                  argsList += visitJsonObject2(operand.get(j)).asInstanceOf[Argument]
                }
                argsList.toList
              } else Nil
            FunApplExp(exp1, args)
          case "WhileExp" | "While" =>
            val cond: Exp = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val body = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            WhileExp(cond, body)
          case "ForExp" | "For" =>
            val pattern: Pattern = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Pattern]
            val cond: Exp = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            val body = visitJsonObject2(operand.getJSONObject(i+2)).asInstanceOf[Exp]
            ForExp(pattern, cond, body)
          case "IfExp" | "If" | "Conditional" =>
            val cond = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val trueBranch = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            val falseBranch =
              if (operand.length() == i+3)
                Some(visitJsonObject2(operand.getJSONObject(i+2)).asInstanceOf[Exp])
              else None
            IfExp(cond, trueBranch, falseBranch)
          case "QuantifiedExp" =>
            val quantifier = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Quantifier]
            val bindings = getRngBindingList(operand.getJSONObject(i+1)).asInstanceOf[List[RngBinding]]
            val exp = visitJsonObject2(operand.getJSONObject(i+2)).asInstanceOf[Exp]
            QuantifiedExp(quantifier, bindings, exp)
          case "CollectionComprExp" =>
            var kind = getCollectionKind(operand.getString(i))
            var exp1 = visitJsonObject2(operand.get(i+1)).asInstanceOf[Exp]
            var exp2 = visitJsonObject2(operand.get(i+2)).asInstanceOf[Exp]
            val bindings: MList[RngBinding] = MList()
            for (j <- Range(i+3, operand.length())) {
              bindings += visitJsonObject2(operand.get(j)).asInstanceOf[RngBinding]
            }
            CollectionComprExp(kind, exp1, bindings.toList, exp2)
          case "LambdaExp" | "Lambda" =>
            val pat = getPattern(operand.getJSONObject(i)).asInstanceOf[Pattern]
            val exp = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            LambdaExp(pat, exp)
          case "ResultExp" | "Result" => ResultExp
          case "TypeCastCheckExp" | "TypeCastCheck" =>
            TypeCastCheckExp(operand.getBoolean(i),
              visitJsonObject2(operand.get(i+1)).asInstanceOf[Exp],
              visitJsonObject2(operand.get(i+2)).asInstanceOf[Type])
          case "TupleExp" | "Tuple" =>
            val expList: MList[Exp] = MList()
            for (j <- Range(i, operand.length)) {
              expList += (visitJsonObject2(operand.get(j)).asInstanceOf[Exp])
            }
            TupleExp(expList.toList)
          case "Forall" => Forall
          case "Exists" => Exists
          case "IntegerLiteral" | "LiteralInteger" =>
            var jo : JSONObject = obj
            if ( operand != null ) jo = operand.get(i).asInstanceOf[JSONObject]
            IntegerLiteral(jo.getInt("integer"))
          case "RealLiteral" | "LiteralReal" => // was FloatingPointLiteral
            //RealLiteral(java.lang.Float.parseFloat(operand.get(1).toString)) // was: operand.getString(1)
            val bd = new java.math.BigDecimal(obj.get("double").toString).setScale(8, java.math.BigDecimal.ROUND_UNNECESSARY)
            //println(bd.formatted("%f"))
            RealLiteral(bd)
          case "CharacterLiteral" | "LiteralCharacter" =>
            CharacterLiteral(obj.get("char").asInstanceOf[Char])
          case "BooleanLiteral" | "LiteralBoolean" =>
            BooleanLiteral(obj.getBoolean("boolean"))
          case "StringLiteral" | "LiteralString" =>
            StringLiteral(obj.getString("string"))
          case "BoolType"   => BoolType
          case "IntType"    => IntType
          case "RealType"   => RealType
          case "StringType" => StringType
          case "UnitType"   => UnitType
          case "CharType"   => CharType
          case "IdentType" =>
            IdentType(visitJsonObject2(operand.get(i)).asInstanceOf[QualifiedName],
              null) // missing type parameters I think (null)
          case "Multiplicity" =>
            var exp1 = visitJsonObject2(operand.get(i)).asInstanceOf[Exp]
            var exp2 =
              if (operand.length() == i+1)
                Some(visitJsonObject2(operand.get(i)).asInstanceOf[Exp])
              else
                None
            Multiplicity(exp1, exp2)
          case "Plus" | "Add"    => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, ADD, exp2)
          case "Sub" | "Minus"   => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, SUB, exp2)
          case "Times" | "Multiply"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, MUL, exp2)
          case "Divide"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, DIV, exp2)
          case "Modulo"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, REM, exp2)
          case "LTE" | "LessEquals"    => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, LTE, exp2)
          case "GTE" | "GreaterEquals"    => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, GTE, exp2)
          case "LT" | "Less"     => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, LT, exp2)
          case "GT" | "Greater"     => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, GT, exp2)
          case "EQ" | "Equals"     => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, EQ, exp2)
          case "NotEQ" | "NEQ" | "NoteEquals" => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, NEQ, exp2)
          case "IsIn"   => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, ISIN, exp2)
          case "NotIn"   => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, NOTISIN, exp2)
          case "Subset"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, SUBSET, exp2)
          case "Psubset" => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, PSUBSET, exp2)
          case "Union"   => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, SETUNION, exp2)
          case "Inter"   => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, SETINTER, exp2)
          case "And"     => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, AND, exp2)
          case "Or"      => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, OR, exp2)
          case "Tuples"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, TUPLEINDEX, exp2)
          case "Concat"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, LISTCONCAT, exp2)
          case "Assign"  => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, ASSIGN, exp2)
          case "Implies" => 
            val exp1 = visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            BinExp(exp1, IMPL, exp2)
          case "BinExp" =>
            val operator: BinaryOp =
              operand.getString(i) match {
                case "Plus"    => ADD
                case "Minus"   => SUB
                case "Times"   => MUL
                case "Divide"  => DIV
                case "Modulo"  => REM
                case "LTE"     => LTE
                case "GTE"     => GTE
                case "LT"      => LT
                case "GT"      => GT
                case "EQ"      => EQ
                case "NotEQ"   => NEQ
                case "IsIn"    => ISIN
                case "NotIn"   => NOTISIN
                case "Subset"  => SUBSET
                case "Psubset" => PSUBSET
                case "Union"   => SETUNION
                case "Inter"   => SETINTER
                case "And"     => AND
                case "Or"      => OR
                case "Tuples"  => TUPLEINDEX
                case "Concat"  => LISTCONCAT
                case "Assign"  => ASSIGN
                case "Implies" => IMPL
                case _ =>
                  println(operand.get(0).asInstanceOf[JSONObject].get("element"))
                  null
              }
            val exp1 = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            val exp2 = visitJsonObject2(operand.getJSONObject(i+2)).asInstanceOf[Exp]
            BinExp(exp1, operator, exp2)
          case "Neg" | "Negative" | "NEG" => 
            UnaryExp(NEG, visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp])
          case "Not"  => 
            UnaryExp(NOT, visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp])
          case "Prev" => 
            UnaryExp(PREV, visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp])
          case "UnaryExp" =>
            val operator: UnaryOp =
              operand.getString(i) match {
                case "Neg"  => NEG
                case "Not"  => NOT
                case "Prev" => PREV
                case op @ _ =>
                  println("unknown operator " + op)
                  System.exit(-1).asInstanceOf[Nothing]
              }
            UnaryExp(operator, visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp])
          case "PositionalArgument" =>
            PositionalArgument(visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp])
          case "NamedArgument" =>
            val ident = operand.getString(i)
            val exp = visitJsonObject2(operand.getJSONObject(i+1)).asInstanceOf[Exp]
            NamedArgument(ident, exp)
          case "RngBinding" =>
            val patterns: MList[Pattern] = MList()
            for (j <- Range(i+1, operand.length())) {
              patterns += visitJsonObject2(operand.get(j)).asInstanceOf[Pattern]
            }
            val collection = visitJsonObject2(operand.get(i)).asInstanceOf[Collection]
            RngBinding(patterns.toList, collection)
          case "ExpCollection" =>
            ExpCollection(visitJsonObject2(operand.get(i)).asInstanceOf[Exp])
          case "IdentPattern" =>
            IdentPattern(operand.get(i).asInstanceOf[String])
//          case key @ _ =>
//            if (opIsElementValue) {
//              // Assume that this is a reference to some Operation, either a FunctionAppl or Constructor
//              val exp1 = visitJsonObject2(key).asInstanceOf[Exp]
//              val args =
//                if (operand.length > 1) {
//                  val argsList: MList[Argument] = MList()
//                  for (i <- Range(1, operand.length())) {
//                    argsList += visitJsonObject2(operand.get(i)).asInstanceOf[Argument]
//                  }
//                  argsList.toList
//                } else Nil
//              FunApplExp(exp1, args)
//            } else {
//              println("Unknown keys encountered in JSON string! (3) : " + key)
//              System.exit(-1).asInstanceOf[Nothing]
//            }
//        }
      case "IdentExp" | "ElementValue" =>
        IdentExp(obj.get("element").asInstanceOf[String])
      // Non-Expr, similar to regular JSON
      case "Model" =>
        var packageName: Option[PackageDecl] =
          if (obj.keySet().contains("packageName")) Some(visitJsonObject2(obj.getJSONObject("packageName"))).asInstanceOf[Option[PackageDecl]]
          else None
        var annotations = visitJsonArray(obj.get("annotations"), visitJsonObject2).asInstanceOf[List[AnnotationDecl]]
        var imports = visitJsonArray(obj.get("imports"), visitJsonObject2).asInstanceOf[List[ImportDecl]]
        var decls = visitJsonArray(obj.get("decls"), visitJsonObject2).asInstanceOf[List[TopDecl]]
        Model(packageName, imports, annotations, decls)
      case "AnnotationDecl" =>
        AnnotationDecl(obj.getString("name"), visitJsonObject2(obj.get("ty")).asInstanceOf[Type])
      case "Annotation" =>
        Annotation(obj.getString("name"), visitJsonObject2(obj.get("exp")).asInstanceOf[Exp])
      case "PackageDecl" =>
        var name: QualifiedName = visitJsonObject2(obj.get("name")).asInstanceOf[QualifiedName]
        PackageDecl(name)
      case "ImportDecl" =>
        var name: QualifiedName = visitJsonObject2(obj.get("name")).asInstanceOf[QualifiedName]
        var star: Boolean =
          if (obj.get("star").equals("true")) true
          else false
        ImportDecl(name, star)
      case "EntityDecl" =>
        var annotations = visitJsonArray(obj.get("annotations"), visitJsonObject2).asInstanceOf[List[Annotation]]
        var entityToken =
          if (obj.get("entityToken").equals("class")) ClassToken
          else if (obj.get("entityToken").equals("assoc")) AssocToken
          else IdentifierToken(obj.getString("entityToken"))
        var keyword =
          if (obj.keySet().contains("keyword")) Some(obj.getString("keyword"))
          else None
        var ident: String = obj.get("ident").toString()
        var typeParams: List[TypeParam] =
          if (obj.keySet.contains("typeParams"))
            visitJsonArray(obj.get("typeParams"), visitJsonObject2).asInstanceOf[List[TypeParam]]
          else Nil
        var extending: List[Type] =
          visitJsonArray(obj.get("extending"), visitJsonObject2).asInstanceOf[List[Type]]
        var members: List[MemberDecl] =
          visitJsonArray(obj.get("members"), visitJsonObject2).asInstanceOf[List[MemberDecl]]
        EntityDecl(annotations, entityToken, keyword, ident, typeParams, extending, members)
      case "TypeParam" =>
        var ident: String = obj.get("ident").toString()
        var bound: Option[TypeBound] =
          if (obj.keySet().contains("bound")) Some(visitJsonObject2(obj.get("bound")).asInstanceOf[TypeBound])
          else None
        TypeParam(ident, bound)
      case "TypeBound" =>
        visitJsonArray(obj.get("types"), visitJsonObject2).asInstanceOf[List[Type]]
      case "TypeDecl" =>
        var ident: String = obj.get("ident").toString()
        var typeParams: List[TypeParam] = visitJsonArray(obj.get("typeParam"), visitJsonObject2).asInstanceOf[List[TypeParam]]
        var ty =
          if (obj.keySet().contains("ty")) Some(visitJsonObject2(obj.get("ty")).asInstanceOf[Type])
          else None
        TypeDecl(ident, typeParams, ty)
      case "PropertyDecl" =>
        var modifiers =
          visitJsonArray(obj.get("modifiers"), getModifier).asInstanceOf[List[PropertyModifier]]
        var name = obj.getString("name")
        var ty = visitJsonObject2(obj.get("ty")).asInstanceOf[Type]
        var multiplicity =
          if (obj.keySet.contains("multiplicity")) Some(visitJsonObject2(obj.get("multiplicity")).asInstanceOf[Multiplicity])
          else None
        var assignment =
          if (obj.keySet.contains("assignment")) Some(obj.getBoolean("assignment"))
          else None
        var expr: Option[Exp] =
          if (obj.keySet().contains("expr")) Some(visitJsonObject2(obj.get("expr")).asInstanceOf[Exp])
          else None
        PropertyDecl(modifiers, name, ty, multiplicity, assignment, expr)
      case "FunDecl" =>
        var ident: String = obj.get("ident").toString()
        var typeParams = visitJsonArray(obj.get("typeParams"), visitJsonObject2).asInstanceOf[List[TypeParam]]
        var params = visitJsonArray(obj.get("params"), visitJsonObject2).asInstanceOf[List[Param]]
        var ty =
          if (obj.keySet().contains("ty")) Some(visitJsonObject2(obj.get("ty")).asInstanceOf[Type])
          else None
        var spec = visitJsonArray(obj.get("spec"), visitJsonObject2).asInstanceOf[List[FunSpec]]
        var body: List[MemberDecl] = visitJsonArray(obj.get("body"), visitJsonObject2).asInstanceOf[List[MemberDecl]]
        FunDecl(ident, typeParams, params, ty, spec, body)
      case "FunSpec" =>
        FunSpec(obj.getBoolean("pre"), visitJsonObject2(obj.get("exp")).asInstanceOf[Exp])
      case "ConstraintDecl" =>
        var name: Option[String] =
          if (obj.keySet().contains("name")) Some(obj.get("name").toString())
          else None
        var exp = visitJsonObject2(obj.get("exp")).asInstanceOf[Exp]
        ConstraintDecl(name, exp)
      case "ExpressionDecl" =>
        var exp = visitJsonObject2(obj.get("exp")).asInstanceOf[Exp]
        ExpressionDecl(exp)
      case "Multiplicity" =>
        var exp1 = visitJsonObject2(obj.get("exp1")).asInstanceOf[Exp]
        val exp2: Option[Exp] =
          if (obj.keySet().contains("falseBranch")) Some(visitJsonObject2(obj.get("exp2")).asInstanceOf[Exp])
          else None
        Multiplicity(exp1, exp2)
      case "Param" =>
        Param(obj.getString("name"), visitJsonObject2(obj.get("ty")).asInstanceOf[Type])
      case "Quantifier" =>
        obj.getString("element") match {
          case "Forall" => Forall
          case "Exists" => Exists
        }
      case "TypeCastCheckExp" =>
        TypeCastCheckExp(obj.getBoolean("cast"), visitJsonObject2(obj.get("exp")).asInstanceOf[Exp], visitJsonObject2(obj.get("ty")).asInstanceOf[Type])
      case "QualifiedName" =>
        QualifiedName(visitJsonArray(obj.get("names").asInstanceOf[JSONArray], (x => x.asInstanceOf[String])).asInstanceOf[List[String]])
      case key @ _ =>
        if (opIsElementValue) {//} || (key match { case "ElementValue" => true case _ => false })) {
          // Assume that this is a reference to some Operation, either a FunctionAppl or Constructor
          //val exp1 = visitJsonObject2(key).asInstanceOf[Exp]
          val exp1 = IdentExp(key)//visitJsonObject2(operand.getJSONObject(i)).asInstanceOf[Exp]
          val args =
            if (operand.length > 1) {
              val argsList: MList[Argument] = MList()
              for (j <- Range(i, operand.length())) {
                argsList += PositionalArgument(visitJsonObject2(operand.get(j)).asInstanceOf[Exp])
              }
              argsList.toList
            } else Nil
          FunApplExp(exp1, args)
        } else {
          println("Unknown keys encountered in JSON string! (2) : " + key)
          System.exit(-1).asInstanceOf[Nothing]
        }
//      case key @ _ =>
//        println("Unknown keys encountered in JSON string! (2) : " + key)
//        System.exit(-1).asInstanceOf[Nothing]
    }
  }

  // Assuming that the input to this is an expression in JSON string format
  def json2exp2(expressionString: String): String = {
    var tokener: JSONTokener = new JSONTokener(expressionString)
    var jsonObject: JSONObject = new JSONObject(tokener)
    var element: JSONArray = jsonObject.get("elements").asInstanceOf[JSONArray]
    var specialization: JSONObject = element.get(0).asInstanceOf[JSONObject]
    var exp: Exp = visitJsonObject2(specialization.get("specialization").asInstanceOf[JSONObject]).asInstanceOf[Exp]
    exp.toString();
  }

  def getVisitor(contents: String): (KScalaVisitor, ModelContext) = {

    var input: ANTLRInputStream = new ANTLRInputStream(contents)
    var lexer: ModelLexer = new ModelLexer(input)
    var tokens: CommonTokenStream = new CommonTokenStream(lexer)
    var parser: ModelParser = new ModelParser(tokens)
    var tree = parser.model()
    var ksv: KScalaVisitor = new KScalaVisitor()
    (ksv, tree)
  }

  def getModelFromFile(f: String): Model = {
    var path: Path = Paths.get(f)
    var bytes: Array[Byte] = Files.readAllBytes(path)
    var fileContents: String = new String(bytes, "UTF-8")
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(fileContents)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]
    m
  }

  def getModelFromString(f: String): Model = {
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(f)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]
    m
  }

  def exp2Json(expressionString: String): String = {
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(expressionString)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]

    var exp: Exp = m.decls(0).asInstanceOf[ExpressionDecl].exp
    val array = new JSONArray()
    val operand = new JSONArray()
    val root = new JSONObject()
    ASTOptions.useJson1 = true
    exp.toJson.toString(4)
  }

  def exp2Json2(expressionString: String): String = {
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(expressionString)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]

    var exp: Exp = m.decls(0).asInstanceOf[ExpressionDecl].exp
    val array = new JSONArray()
    val operand = new JSONArray()
    val root = new JSONObject()

    ASTOptions.useJson1 = false

    var elements = exp.toJson
    var specialization = new JSONObject()
    specialization = new JSONObject()
    specialization.put("specialization", elements)
    array.put(specialization)
    var res: JSONObject = root.put("elements", array)
    res.toString(4)
  }

  def exp2KExp(expressionString: String): Exp = {
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(expressionString)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]

    var exp: Exp = m.decls(0).asInstanceOf[ExpressionDecl].exp
    exp
  }

  def exp2KExpList(expressionString: String): List[Exp] = {
    val (ksv: KScalaVisitor, tree: ModelContext) = getVisitor(expressionString)
    var m: Model = ksv.visit(tree).asInstanceOf[Model]
    m.decls.map(x => x.asInstanceOf[ExpressionDecl].exp)
  }

  def getDeclCount(m: Model, d: Class[_]): Int = {
    m.decls.count(decl => (d == decl.getClass))
  }

  def printStats(m: Model) {
    println("Imports: " + m.imports.size)
    println("Entities: " + getDeclCount(m, classOf[EntityDecl]))
    println("Properties: " + getDeclCount(m, classOf[PropertyDecl]))
    println("Functions: " + getDeclCount(m, classOf[FunDecl]))
    println("Types: " + getDeclCount(m, classOf[TypeDecl]))
    println("Expressions: " + getDeclCount(m, classOf[ExpressionDecl]))
    println("Constraints: " + getDeclCount(m, classOf[ConstraintDecl]))
  }

  def analyze(m: Model) {

  }
}
