package k.frontend

import org.json.JSONObject
import java.text._
import org.json.JSONArray
import scala.collection.mutable.Stack

// NOTE: toJson is the correct way of doing JSON
// toJson2 is the MMS way of doing JSON, which in a lot of cases
// is ill defined and in those cases, it is set to toJson
// for expressions, toJson2 is LISP style... 

object ASTOptions {
  var debug = false
  var silent = false
  var useJson1 = true
}

object UtilAST {
  def ???(comment: String): Nothing = {
    println("NOT IMPLEMENTED " + comment)
    null.asInstanceOf[Nothing]
  }

  def ??? : Nothing = ???("")

  def debug(text: String) {
    if (ASTOptions.debug) println(s"[-- debug --: $text]")
  }
}
import UtilAST._
import ClassHierarchy._
import TypeChecker._

object K2SMTException extends Exception

object UtilSMT {
  object Names {
    val mainClass: String = "TopLevelDeclarations"
  }

  var storedModel: Model = null
  var subClassMap: Map[String, List[String]] = Map()
  var constantsToDeclare: List[(String, Type)] = Nil
  var constantCounter: Int = 0

  def reset {
    storedModel = null
    subClassMap = Map()
    constantsToDeclare = Nil
    constantCounter = 0
  }

  def error(msg: String) = {
    val msgFull = s"Unsupported: $msg"
    if (ASTOptions.silent) Misc.silentErrorThrow("K2SMT", msgFull, K2SMTException)
    else Misc.errorThrow("K2SMT", msgFull, K2SMTException)
  }
  def log(msg: String) = if (!ASTOptions.silent) Misc.log("TypeChecker", msg)
  def logDebug(msg: String) = if (ASTOptions.debug && !ASTOptions.silent) Misc.log("TypeChecker", s"DEBUG $msg")
  def warning(msg: String) = Misc.log("TypeChecker", s"Warning $msg")

  def sortEntityDecls(unsortedEntityDecls: List[EntityDecl]): List[EntityDecl] = {
    val entityDeclPairs =
      for (ed1 <- unsortedEntityDecls; ed2 <- TypeChecker.getDirectSubClasses(ed1.ident)) yield (ed1, classes(ed2))
    val sortedEntityDecls = Misc.topologicalSort(entityDeclPairs).toList
    sortedEntityDecls ++ (unsortedEntityDecls.filterNot(sortedEntityDecls.contains(_)))
  }

  def wellFormedType(ty: Type): Boolean =
    ty match {
      case CartesianType(types)          => types forall wellFormedType
      case ParenType(ty)                 => wellFormedType(ty)
      case BoolType | IntType | RealType => true
      case IdentType(_, _)               => true
      case FunctionType(_, _) | SubType(_, _, _) | CharType | StringType | UnitType =>
        UtilSMT.error(s"$ty in local property declaration")
    }

  def ignoreMember(memberDecl: MemberDecl): Boolean = {
    memberDecl match {
      case PropertyDecl(modifiers, name, ty, multiplicity, assignment, expr) =>
        !wellFormedType(ty)
    }
  }

  def memberList2SMT(members: List[MemberDecl], level: Int = 0): String = {
    members match {
      case ExpressionDecl(exp) :: Nil =>
        val expSMT = exp.toSMT
        "  " + ("  " * level) + expSMT + (")" * level)
      case pd @ PropertyDecl(modifiers, name, ty, None, _, exp) :: rest =>
        if(!modifiers.forall(_ == Val))
          UtilSMT.error(s"modifier in $pd")
        if (!wellFormedType(ty))
          UtilSMT.error(s"$ty in local property declaration $pd")
        exp match {
          case Some(e) =>
            val expSMT = e.toSMT
            "  " + ("  " * level) + s"(let (($name $expSMT))\n" +
              memberList2SMT(rest, level + 1)
          case None=>
            UtilSMT.error(s"expression is missing in local property declaration $pd")
        }
      case _ =>
        UtilSMT.error(s"body of function\n${members.mkString("\n")}")
    }
  }

  def getSubClassesTransitive(className: String): List[String] = {
    if (subClassMap contains className)
      subClassMap(className)
    else {
      val classes = getSubClasses(className)
      subClassMap = subClassMap + (className -> classes)
      classes
    }
  }

  def getNewConstant(ty: Type): String = {
    constantCounter += 1
    val constant = s"const__$constantCounter"
    constantsToDeclare ++= List((constant, ty))
    constant
  }

  def constantsIsEmpty: Boolean = constantsToDeclare.isEmpty

  def generateOmittedConstructorParameters: String = {
    var result = ""
    for ((id, ty) <- constantsToDeclare) {
      result += s"(declare-const $id ${ty.toSMT})\n"
    }
    result
  }

  def getDeclaringClass(identExp: Exp): String = {
    if (!identExp.isInstanceOf[IdentExp])
      error(s"Should be an IdentExp: $identExp")
    else {
      val entityDecl: EntityDecl = getOwningEntityDecl(identExp)
      if (entityDecl == null)
        Names.mainClass
      else
        entityDecl.ident
    }
  }

  def getDeclaringClass(className: String, ident: String): String = {
    val typeInfo = decl2TypeEnvi(classes(className)).map(ident)
    val declaringEntityDecl = typeInfo match {
      case PropertyTypeInfo(_, _, _, owning) => owning
      case FunctionTypeInfo(_, owning)       => owning
      case _                                 => error(s"property type or function type expected: $typeInfo")
    }
    declaringEntityDecl.ident
  }

  def derefField(field: String, classes: List[String], level: Int = 0): String = {
    classes match {
      case className :: Nil =>
        s"  ($field (deref-$className this))" + (")" * level) + "\n"
      case className :: rest =>
        s"  (ite (deref-is-$className this) ($field (deref-$className this))\n" +
          derefField(field, rest, level + 1)
    }
  }

  def applyRelevantSubInvariant(classes: List[String], level: Int = 0): String = {
    classes match {
      case Nil =>
        "     " + ("  " * level) + "false" + (")" * level) + "\n"
      case className :: rest =>
        "    " + ("  " * level) + s"(ite (deref-is-$className this) ($className.inv this)\n" +
          applyRelevantSubInvariant(rest, level + 1)
    }
  }

  def isConstructorPredicate(exp: Exp): Boolean = {
    val result = exp match {
      case BinExp(exp1, EQ, FunApplExp(exp2, args)) =>
        isConstructor(exp2)
      case _ => false
    }
    result
  }

  def isClassName(ty: Type): Boolean = {
    ty match {
      case IdentType(QualifiedName(_ :: Nil), _) => true
      case _                                     => false
    }
  }

  def transformModel(model: Model): Model = {
    val Model(packageName, imports, annotations, decls) = model
    var memberDecls: List[MemberDecl] =
      for (decl <- decls if decl.isInstanceOf[MemberDecl]) yield decl.asInstanceOf[MemberDecl]
    val mainClass = EntityDecl(Nil, ClassToken, None, UtilSMT.Names.mainClass, Nil, Nil, memberDecls)
    var newDecls: List[EntityDecl] =
      for (decl <- decls if decl.isInstanceOf[EntityDecl]) yield decl.asInstanceOf[EntityDecl]
    newDecls ++= List(mainClass)
    val newModel = Model(packageName, imports, annotations, newDecls)
    //println(s"---\n$model\n---\n$newModel\n---")
    storedModel = newModel
    newModel
  }
}

private[frontend] object ToStringSupport {
  private val space = "  "
  private var level: Int = 0

  def moveIn {
    level += 1
  }

  def moveOut {
    level -= 1
  }

  def indent: String = space * level

  def requiresPreceedingBlankLine(member: MemberDecl): Boolean =
    member.isInstanceOf[FunDecl] || member.isInstanceOf[ConstraintDecl]
}
import ToStringSupport._

case class Model(packageName: Option[PackageDecl], imports: List[ImportDecl],
                 annotations: List[AnnotationDecl],
                 decls: List[TopDecl]) {

  def toSMT: String = {
    val model: Model = UtilSMT.transformModel(this)
    val entityDecls = UtilSMT.sortEntityDecls(model.decls.asInstanceOf[List[EntityDecl]])

    var result1: String = "" // text before omitted constructor parameter constants
    var result2: String = "" // text after omitted constructor parameter constants
    // result will eventually contain result1 ++ constants ++ result2.
    // This approach is needed since constants need to go before result2 but 
    // in part are computed based on result2.

    // Generate options

    result1 += "; ---------- options: ----------\n"
    result1 += "\n"
    result1 += "(set-option :smt.macro-finder true)\n"
    result1 += "\n"

    // Generate datatypes:

    result1 += "; ---------- datatypes: ----------\n"
    result1 += "\n"
    result1 += "(define-sort Ref () Int)\n"
    result1 += "\n"
    result1 += "(declare-datatypes (T1 T2) ((Tuple2 (mk-Tuple2 (_1 T1)(_2 T2)))))\n"
    result1 += "(declare-datatypes (T1 T2 T3) ((Tuple3 (mk-Tuple3 (_1 T1)(_2 T2)(_3 T3)))))\n"
    result1 += "\n"
    for (ed <- entityDecls) {
      result1 += ed.toSMTDatatype + "\n"
    }
    result1 += "\n"

    // Generate heap:

    result1 += "; ---------- heap: ----------\n"
    result1 += "\n"
    result1 += "(declare-datatypes () ((Any\n"
    for (ed <- entityDecls) {
      result1 += ed.toSMTAnyEntry + "\n"
    }
    result1 += "  null))\n"
    result1 += ")\n"
    result1 += "\n"
    result1 += "(declare-const heap (Array Ref Any))\n"
    result1 += "\n"
    result1 += "(define-fun deref ((ref Ref)) Any\n"
    result1 += "  (select heap ref)\n"
    result1 += ")\n"
    result1 += "\n"

    // Generate class specific is/deref-functions:

    result1 += "; ---------- class specific is/deref-functions: ----------\n"
    result1 += "\n"
    for (ed <- entityDecls) {
      result1 += ed.toSMTIsAndDerefFunctions + "\n"
      result1 += "\n"
    }

    // Generate isa-functions (not used right now):    

    result1 += "; ---------- isa-functions: ----------\n"
    result1 += "\n"
    for (ed <- entityDecls) {
      result1 += ed.toSMTIsAFunction + "\n"
      result1 += "\n"
    }

    // Generate getters:    

    result1 += "; ---------- getters: ----------\n"
    result1 += "\n"
    for (ed <- entityDecls) {
      val gettersSMT: String = ed.toSMTGetterFunctions
      if (gettersSMT != "") {
        result1 += gettersSMT + "\n"
        result1 += "\n"
      }
    }

    // --------------------------------------------
    // --- Here switching to result2.           ---
    // --- Constructor constants to be inserted ---
    // --- between result1 and result2          ---
    // --------------------------------------------

    // Generate methods:

    result2 += s"; ---------- methods: ----------\n"
    result2 += "\n"
    for (ed <- entityDecls) {
      val methodsSMT: String = ed.toSMTMethods
      if (methodsSMT != "") {
        result2 += methodsSMT + "\n"
        result2 += "\n"
      }
    }

    // Generate invariants:

    result2 += s"; ---------- invariants: ----------\n"
    result2 += "\n"
    for (ed <- entityDecls) {
      result2 += s"; --- ${ed.ident}:\n"
      result2 += "\n"
      result2 += s"${ed.toSMTInvariant}\n"
      result2 += "\n"
    }
    result2 += "\n"

    // Generate assertions:

    result2 += s"; ---------- assertions: ----------\n"
    result2 += "\n"
    for (ed <- entityDecls) {
      result2 += ed.toSMTAssert + "\n"
    }
    result2 += "\n"
    //    result2 += "(apply quasi-macros)"

    // Add constants for omitted constructor parameters:

    var constants: String = ""
    if (!UtilSMT.constantsIsEmpty) {
      constants += s"; ---------- constructor parameter constants: ----------\n"
      constants += "\n"
      constants += UtilSMT.generateOmittedConstructorParameters + "\n"
      constants += "\n"
    }
    result1 + constants + result2
  }

  override def toString = {
    var result =
      packageName match {
        case Some(p) => p + "\n"
        case None    => ""
      }
    if (!imports.isEmpty) {
      result += "\n"
      for (imp <- imports) {
        result += imp + "\n"
      }
    }

    if (!annotations.isEmpty) {
      result += "\n"
      for (annotationDecl <- annotations) {
        result += annotationDecl + "\n"
      }
    }

    if (!decls.isEmpty) {
      result += "\n"
      for (decl <- decls) {
        result += decl + "\n\n"
      }
    }

    result
  }

  def toJson: JSONObject = {
    val model = new JSONObject()
    val theImports = new JSONArray()
    val theDecls = new JSONArray()
    val theAnnotations = new JSONArray()

    model.put("type", "Model")
    packageName match {
      case None =>
      case Some(pckdecl) =>
        model.put("packageName", pckdecl.toJson)
    }

    for (imp <- imports) theImports.put(imp.toJson)
    model.put("imports", theImports)

    for (annotation <- annotations) theAnnotations.put(annotation.toJson)
    model.put("annotations", theAnnotations)

    for (decl <- decls) theDecls.put(decl.toJson)
    model.put("decls", theDecls)

  }

}

case class PackageDecl(name: QualifiedName) {
  override def toString = s"package $name"

  def toJson: JSONObject = {
    val packagedecl = new JSONObject()
    packagedecl.put("type", "PackageDecl")
    packagedecl.put("name", name.toJson)
  }

}

case class AnnotationDecl(name: String, ty: Type) extends TopDecl {
  override def toString = s"annotation $name : $ty"

  override def toJson1 = {
    val annotationDecl = new JSONObject()
    annotationDecl.put("type", "AnnotationDecl")
    annotationDecl.put("name", name)
    annotationDecl.put("ty", ty.toJson)
  }
  override def toJson2 = toJson1
}

case class Annotation(name: String, exp: Exp) {
  override def toString = s"@$name($exp)"

  def toJson = {
    val annotation = new JSONObject()
    annotation.put("type", "Annotation")
    annotation.put("name", name)
    annotation.put("exp", exp.toJson)
  }
}

case class QualifiedName(names: List[String]) {
  def toSMT: String = {
    assert(false, "we should never reach here")
    def dot2Lisp(names: List[String]): String =
      names match {
        case Nil          => "this"
        case name :: rest => s"($name ${dot2Lisp(rest)})"
      }
    dot2Lisp(names.reverse)
  }

  override def toString = names.mkString(".")

  def toJson: JSONObject = {
    val qualifiedName = new JSONObject()
    val theNames = new JSONArray()
    for (name <- names) theNames.put(name)
    qualifiedName.put("names", theNames)
    qualifiedName.put("type", "QualifiedName")
  }
  
  // FIXME -- This assumes that the consumer of the JSON will be able to parse
  // the qualified name.
  // Don't we want to pass back the unique identifier of the symbol?
  // Do we have a lookup table?
  // Or, we could assume a QualifiedName function externally that returns
  // the ElementValue.
  def toJson2: JSONObject = {
    new JSONObject().put("type", "ElementValue").put("element", toString)
  }
}

case class ImportDecl(name: QualifiedName, star: Boolean) {
  override def toString =
    "import " + name + (if (star) ".*" else "")

  def toJson: JSONObject = {
    val importdecl = new JSONObject()
    importdecl.put("type", "ImportDecl")
    importdecl.put("name", name.toJson)
    importdecl.put("star", star.toString)
  }

}

trait TopDecl {
  def toSMT: String = ???
  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }
  def toJson1: JSONObject
  def toJson2: JSONObject
}

case class EntityDecl(
  var annotations: List[Annotation],
  entityToken: EntityToken,
  keyword: Option[String],
  ident: String,
  typeParams: List[TypeParam],
  extending: List[Type],
  members: List[MemberDecl]) extends TopDecl {

  def toSMTDatatype: String = {
    val propertyDecls = getAllPropertyDecls
    if (propertyDecls.isEmpty) {
      s"(declare-sort $ident)"
    } else {
      val constr = s"mk-$ident"
      val fields = propertyDecls.map(_.toSMT(ident)).mkString
      s"(declare-datatypes () (($ident ($constr $fields))))"
    }
  }

  def toSMTAnyEntry: String =
    s"  (lift-$ident (sel-$ident $ident))"

  def toSMTIsAndDerefFunctions: String = {
    var result: String = ""
    result += s"(define-fun deref-is-$ident ((this Ref)) Bool\n"
    result += s"  (is-lift-$ident (deref this))\n"
    result += s")\n"
    result += "\n"
    result += s"(define-fun deref-$ident ((this Ref)) $ident\n"
    result += s"  (sel-$ident (deref this))\n"
    result += ")"
    result
  }

  def toSMTIsAFunction: String = {
    var result: String = ""
    val subClasses = UtilSMT.getSubClassesTransitive(ident)
    result += s"(define-fun deref-isa-$ident ((this Ref)) Bool\n"
    if (subClasses.isEmpty) {
      result += s"  (deref-is-$ident this)\n"
    } else {
      result += "  (or\n"
      for (cn <- ident :: subClasses) {
        result += s"    (deref-is-$cn this)\n"
      }
      result += "  )\n"
    }
    result += ")"
    result
  }

  def toSMTGetterFunctions: String = {
    var result: String = ""
    val propertyDecls: List[PropertyDecl] = getPropertyDecls
    if (!propertyDecls.isEmpty) {
      result += s"; --- $ident:\n"
      result += "\n"
      val subClasses = UtilSMT.getSubClassesTransitive(ident)
      var firstTime = true
      for (pd <- propertyDecls) {
        if (firstTime)
          firstTime = false
        else
          result += "\n\n"
        val field = pd.name
        val tySMT = pd.ty.toSMT
        result += s"(define-fun $ident.$field ((this Ref)) $tySMT\n"
        result += UtilSMT.derefField(field, ident :: subClasses)
        result += ")"
      }
    }
    result
  }

  def toSMTMethods: String = {
    var result: String = ""
    val funDecls = getFunDecls
    if (!funDecls.isEmpty) {
      result += s"; --- $ident:\n"
      result += "\n"
      result += funDecls.map(_.toSMT(ident)).mkString("\n\n")
    }
    result
  }

  def toSMTInvariant: String = {
    var constraints: List[String] = Nil
    // constraints for property definitions of the form: x : T = e
    for (PropertyDecl(_, propertyName, ty, _, Some(false), Some(exp)) <- members) {
      if (UtilSMT.isClassName(ty))
        constraints ::= s"(= (deref ($ident.$propertyName this)) ${exp.toSMT})"
      else
        constraints ::= s"(= ($ident.$propertyName this) ${exp.toSMT})"
    }
    // constraints for embedded references/parts:
    for (PropertyDecl(_, propertyName, IdentType(QualifiedName(typeName :: Nil), _), _, _, _) <- members) {
      // if (typeName != "Any")
      constraints ::= s"(deref-isa-$typeName ($ident.$propertyName this))"
    }
    // constraints for constraint decls:
    for (ConstraintDecl(name, exp) <- members) {
      constraints ::= exp.toSMT
    }

    // create resulting string:
    var result: String = ""

    // The inv function:
    val directSuperClasses: List[String] = getDirectSuperClasses(ident)
    result += s"(define-fun $ident.inv ((this Ref)) Bool\n"
    if (directSuperClasses.isEmpty && constraints.isEmpty) {
      result += "  true\n"
    } else {
      result += s"  (and\n"
      for (directSuperClass <- getDirectSuperClasses(ident)) {
        result += s"    ($directSuperClass.inv this)\n"
      }
      for (constraint <- constraints.reverse) {
        result += s"    $constraint\n"
      }
      result += s"  )\n"
    }
    result += s")\n"
    result += "\n"

    // The inv.nosub function:
    result += s"(define-fun $ident.inv.nosub ((this Ref)) Bool\n"
    result += s"  (and\n"
    result += s"    (deref-is-$ident this)\n"
    result += s"    ($ident.inv this)\n"
    result += s"  )\n"
    result += s")\n"
    result += "\n"

    // Enforce invariant:    
    result += s"(assert (forall ((this Ref))\n"
    result += s"  (=> (deref-is-$ident this) ($ident.inv this))\n"
    result += s"))"

    result
  }

  def toSMTAssert: String =
    s"(assert (exists ((instanceOf$ident Ref)) (${ident}.inv.nosub instanceOf$ident)))"

  def getPropertyDecls: List[PropertyDecl] =
    for (m <- members if m.isInstanceOf[PropertyDecl] && !UtilSMT.ignoreMember(m)) yield m.asInstanceOf[PropertyDecl]

  def getAllPropertyDecls: List[PropertyDecl] = {
    val propertyDeclsOfSuperClasses: List[PropertyDecl] =
      (for (superClass <- getSuperClasses(ident)) yield classes(superClass).getPropertyDecls).flatten
    propertyDeclsOfSuperClasses ++ getPropertyDecls
  }

  def getFunDecls: List[FunDecl] =
    for (m <- members if m.isInstanceOf[FunDecl]) yield m.asInstanceOf[FunDecl]

  override def toString = {
    var result = ""
    if (!annotations.isEmpty) {
      for (annotation <- annotations) {
        result += annotation + "\n"
      }
      result += "\n"
    }
    result += s"$entityToken"
    keyword match {
      case None      =>
      case Some(str) => result += s" <$str>"
    }
    result += s" $ident"
    if (!typeParams.isEmpty) {
      result += s"[${typeParams.mkString(",")}]"
    }
    if (!extending.isEmpty) {
      result += s" extending ${extending.mkString(",")}"
    }
    if (!members.isEmpty) {
      result += " {\n"
      moveIn
      for (member <- members) {
        if (requiresPreceedingBlankLine(member)) result += "\n"
        result += indent + member + "\n"
      }
      moveOut
      result += "}"
    }
    result
  }

  def toJson1 = {
    val entitydecl = new JSONObject()
    val theAnnotations = new JSONArray()
    val theTypeParams = new JSONArray()
    val theExtending = new JSONArray()
    val theMembers = new JSONArray()

    entitydecl.put("type", "EntityDecl")
    for (annotation <- annotations) theAnnotations.put(annotation.toJson)
    entitydecl.put("annotations", theAnnotations)
    entitydecl.put("entityToken", entityToken.toJson)
    keyword match { case Some(e) => entitydecl.put("keyword", e) case _ => }
    entitydecl.put("ident", ident)
    for (typeParam <- typeParams) theTypeParams.put(typeParam.toJson)
    entitydecl.put("typeparams", theTypeParams)
    for (t <- extending) theExtending.put(t.toJson)
    entitydecl.put("extending", theExtending)
    for (member <- members) theMembers.put(member.toJson)
    entitydecl.put("members", theMembers)
  }

  def toJson2 = toJson1
}

trait EntityToken {
  def toJson: String
}

case object ClassToken extends EntityToken {
  override def toString = "class"
  override def toJson = toString
}

case object AssocToken extends EntityToken {
  override def toString = "assoc"
  override def toJson = toString
}

case class IdentifierToken(name: String) extends EntityToken {
  override def toString = name
  override def toJson = toString
}

case class TypeParam(ident: String, bound: Option[TypeBound]) {
  override def toString =
    bound match {
      case None      => ident
      case Some(tyb) => s"$ident : $tyb"
    }

  def toJson: JSONObject = {
    val typeparam = new JSONObject
    typeparam.put("type", "TypeParam")
    typeparam.put("indent", ident)
    bound match {
      case None => typeparam
      case Some(bound) =>
        typeparam.put("bound", bound.toJson)
    }
  }

}

case class TypeBound(types: List[Type]) {
  override def toString = types.mkString(" + ")

  def toJson = {
    val typebound = new JSONObject()
    val theTypes = new JSONArray()

    typebound.put("type", "TypeBound")
    for (t <- types) theTypes.put(t.toJson)
    typebound.put("types", theTypes)
  }

}

trait MemberDecl extends TopDecl {
  def toSMT(className: String): String = ???
  var annotations: List[Annotation] = null
}

case class TypeDecl(ident: String,
                    typeParams: List[TypeParam],
                    ty: Option[Type]) extends MemberDecl {
  override def toString = {
    var result: String = s"type $ident"
    if (!typeParams.isEmpty) {
      result += s"[${typeParams.mkString(",")}]"
    }
    ty match {
      case None =>
      case Some(t) =>
        result += s" = $t"
    }
    result
  }

  override def toJson1 = {
    val typedecl = new JSONObject()
    val params = new JSONArray()
    typedecl.put("ident", ident)
    for (typeParam <- typeParams) params.put(typeParam.toJson)
    typedecl.put("type", "TypeDecl")
    typedecl.put("params", params)
    if (ty.nonEmpty) typedecl.put("ty", ty.get.toJson)
    else typedecl
  }

  override def toJson2 = toJson1
}

case class PropertyDecl(modifiers: List[PropertyModifier],
                        name: String,
                        ty: Type,
                        multiplicity: Option[Multiplicity],
                        assignment: Option[Boolean],
                        expr: Option[Exp]) extends MemberDecl {

  override def toSMT(className: String): String = s"($name ${ty.toSMT})"

  override def toString = {
    var result = ""
    if (!modifiers.isEmpty) {
      result += modifiers.mkString(" ") + " "
    }
    result += name
    result += ":" + ty
    if (multiplicity.nonEmpty) result += multiplicity.get
    if (expr.nonEmpty) {
      if (assignment.nonEmpty) result += (if (assignment.get) " := " else " = ") + expr.get
      else {
        println("Non-empty expression for property declaration, but assignment type not specified.")
        System.exit(-1).asInstanceOf[String]
      }
    }
    result
  }

  override def toJson1 = {
    val propertydecl = new JSONObject()
    val theModifiers = new JSONArray()
    propertydecl.put("type", "PropertyDecl")

    for (modifier <- modifiers) theModifiers.put(modifier.toJson)

    propertydecl.put("modifiers", theModifiers)
    propertydecl.put("name", name)
    propertydecl.put("ty", ty.toJson)
    multiplicity match { case Some(m) => propertydecl.put("multiplicity", m.toJson) case None => }
    assignment match { case Some(b) => propertydecl.put("assignment", b) case None => }
    expr match { case Some(e) => propertydecl.put("expr", e.toJson) case None => }
    propertydecl
  }

  override def toJson2 = toJson1
  // Below is more accurate; commenting it out since it might break a demo.
//  {
//    val elementJson = new JSONObject()
//    val specialization = new JSONObject()
//    elementJson.put("name", name)
//    elementJson.put("specialization", specialization)
//    specialization.put("type", "Property")
//    specialization.put("propertyType", ty.toString)
//    multiplicity match {
//      case None =>
//      case Some(m) => { 
//        specialization.put("lower", m.exp1.toJson)
//        m.exp2 match { 
//          case Some(e2) => specialization.put("upper", e2.toJson)
//          case None =>
//        }
//      }
//    }
//    
//    expr match {
//      case None =>
//      case Some(e) => {
//        val valueArray = new JSONArray()
//        specialization.put("value", valueArray)
//        valueArray.put(e.toJson)
//      }
//    }
//    elementJson
//  }
}

trait PropertyModifier {
  def toJson: String
}
case object Part extends PropertyModifier {
  override def toString = "part"
  def toJson = toString
}
case object Var extends PropertyModifier {
  override def toString = "var"
  def toJson = toString
}
case object Val extends PropertyModifier {
  override def toString = "val"
  def toJson = toString
}
case object Ordered extends PropertyModifier {
  override def toString = "ordered"
  def toJson = toString
}
case object Unique extends PropertyModifier {
  override def toString = "unique"
  def toJson = toString
}
case object Source extends PropertyModifier {
  override def toString = "source"
  def toJson = toString
}
case object Target extends PropertyModifier {
  override def toString = "target"
  override def toJson = toString
}

case class FunSpec(pre: Boolean, exp: Exp) {
  override def toString =
    if (pre) s"pre $exp" else s"post $exp"

  def toJson = {
    val funspec = new JSONObject()
    funspec.put("type", "FunSpec")
    funspec.put("pre", pre.toString)
    funspec.put("exp", exp.toJson)
  }

}

case class Param(name: String, ty: Type) {
  def toSMT: String = s"($name ${ty.toSMT})"

  def toSMTType: String = ty.toSMT

  override def toString = s"$name:$ty"

  def toJson = {
    val param = new JSONObject()
    param.put("type", "Param")
    param.put("name", name)
    param.put("ty", ty.toJson)
  }
}

case class FunDecl(ident: String,
                   typeParams: List[TypeParam],
                   params: List[Param],
                   ty: Option[Type],
                   spec: List[FunSpec],
                   body: List[MemberDecl]) extends MemberDecl {
  
  override def toSMT(className: String): String = {
    var result: String = ""
    val resultType: String = ty match {
      case Some(t) =>
        if (!UtilSMT.wellFormedType(t))
          UtilSMT.error(s"function return type $t in $this")
        else
          t.toSMT
      case None =>
        UtilSMT.error(s"Missing return type (= Unit) $this")
    }
    if (body == Nil) {
      val parameterTypes: String = s"Ref " + params.map(_.toSMTType).mkString(" ")
      result += s"(declare-fun $className.$ident ($parameterTypes) $resultType)"
    } else {
      val parameters: String = s"(this Ref)" + params.map(_.toSMT).mkString
      val bodySMT = UtilSMT.memberList2SMT(body)
      result += s"(define-fun $className.$ident ($parameters) $resultType\n"
      result += s"$bodySMT\n"
      result += ")"
    }
    result
  }
 
  override def toString = {
    var result = s"fun $ident"
    if (typeParams.size > 0) {
      result += "[" + typeParams.mkString(",") + "]"
    }
    if (params.size > 0) {
      result += "(" + params.mkString(",") + ")"
    }
    ty match {
      case Some(ty) => result += " : " + ty
      case _        =>
    }
    result += "\n"
    if (!spec.isEmpty) {
      moveIn
      for (sp <- spec) {
        result += indent + sp + "\n"
      }
      moveOut
    }
    if (!body.isEmpty) {
      result += indent + "{\n"
      moveIn
      for (member <- body) {
        result += indent + member + "\n"
      }
      moveOut
      result += indent + "}"
    }
    result
  }

  override def toJson1 = {
    val fundecl = new JSONObject()
    val theTypeParams = new JSONArray()
    val theParams = new JSONArray()
    val theSpec = new JSONArray()
    val theBody = new JSONArray()
    fundecl.put("type", "FunDecl")
    fundecl.put("ident", ident)
    for (tp <- typeParams) theTypeParams.put(tp.toJson)
    fundecl.put("typeParams", theTypeParams)
    for (p <- params) theParams.put(p.toJson)
    fundecl.put("params", theParams)
    ty match { case Some(t) => fundecl.put("ty", t.toJson) case None => }
    for (s <- spec) theSpec.put(s.toJson)
    fundecl.put("spec", theSpec)
    for (member <- body) theBody.put(member.toJson)
    fundecl.put("body", theBody)
  }

  override def toJson2 = toJson1
}

case class ConstraintDecl(name: Option[String], exp: Exp) extends MemberDecl {
  override def toSMT(className: String): String = ???

  override def toString =
    name match {
      case None =>
        s"req $exp"
      case Some(n) =>
        s"req $n: $exp"
    }

  override def toJson1 = {
    val constraintdecl = new JSONObject
    constraintdecl.put("type", "ConstraintDecl")
    name match {
      case None =>
      case Some(ident) =>
        constraintdecl.put("name", ident)
    }
    constraintdecl.put("exp", exp.toJson)
  }

  override def toJson2 = toJson1
}

case class ExpressionDecl(exp: Exp) extends MemberDecl {
  override def toString = exp.toString

  override def toJson1 = {
    val expressiondecl = new JSONObject()
    expressiondecl.put("type", "ExpressionDecl")
    expressiondecl.put("exp", exp.toJson)
  }

  override def toJson2 = toJson1

}

trait Exp {
  def toSMT: String = ???
  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }
  def toJson1: JSONObject
  def toJson2: JSONObject
}

case class ParenExp(exp: Exp) extends Exp {
  override def toSMT: String = s"${exp.toSMT}"

  override def toString = s"($exp)"

  override def toJson1 = {
    val expression = new JSONObject()

    expression.put("exp", exp.toJson)
    expression.put("type", "ParenExp")
  }

  override def toJson2 = {
    exp.toJson2
  }
}

case class IdentExp(ident: String) extends Exp {
  override def toSMT: String =
    if (isLocal(this))
      ident
    else {
      val declaringClass = UtilSMT.getDeclaringClass(this)
      s"($declaringClass.$ident this) "
    }

  override def toString = ident

  override def toJson1 = {
    val expression = new JSONObject()

    expression.put("ident", ident)
    expression.put("type", "IdentExp")
  }

  override def toJson2 = {
    val expObj = new JSONObject()

    expObj.put("element", ident)
    expObj.put("type", "ElementValue")
  }
}

case class DotExp(exp: Exp, ident: String) extends Exp {
  override def toSMT: String = {
    val expSMT = exp.toSMT
    val classNameOfExp = exp2Type.get(exp).toString
    val classNameOfDeclaringClass = UtilSMT.getDeclaringClass(classNameOfExp, ident)
    s"($classNameOfDeclaringClass.$ident $expSMT)"
  }

  override def toString = s"$exp.$ident"

  override def toJson1 = {
    val expression = new JSONObject()

    expression.put("ident", ident)
    expression.put("exp", exp.toJson)
    expression.put("type", "DotExp")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "getProperty"))
    operand.put(exp.toJson)
    operand.put(new JSONObject().put("type", "LiteralString").put("string",ident))
//    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Dot"))
//    operand.put(exp.toJson)
//    operand.put(new JSONObject().put("type", "LiteralString").put("string",ident))

    expression.put("operand", operand)
    expression.put("type", "Expression")
    
    expression
  }
}

// KH: first argument should be 'exp' really.

case class FunApplExp(exp1: Exp, args: List[Argument]) extends Exp {
  override def toSMT: String = {
    if (isConstructor(exp1)) {
      // constructor application:
      val argMap: Map[String, Exp] = (for (NamedArgument(x, exp) <- args) yield (x -> exp)).toMap
      val IdentExp(ident) = exp1
      val entityDecl = getEntityDecl(ident)
      val argsSMTList: List[String] =
        for (PropertyDecl(_, id, ty, _, _, _) <- entityDecl.getAllPropertyDecls) yield if (argMap contains id) argMap(id).toSMT else UtilSMT.getNewConstant(ty)
      val argsSMT = argsSMTList.mkString(" ")
      s"(lift-$ident (mk-$ident $argsSMT))"
    } else {
      // function application:
      val expSMT: String =
        exp1 match {
          case IdentExp(ident) =>
            val declaringClass = UtilSMT.getDeclaringClass(exp1)
            s"$declaringClass.$ident this"
          case DotExp(expBeforeDot, ident) =>
            val classOfFunction = exp2Type.get(expBeforeDot).toString
            val classNameOfDeclaringClass = UtilSMT.getDeclaringClass(classOfFunction, ident)
            val expSMT = expBeforeDot.toSMT
            s"$classNameOfDeclaringClass.$ident $expSMT"
        }
      val argsSMT: String = args.map(_.toSMT).mkString(" ")
      s"($expSMT $argsSMT)"
    }
  }

  override def toString = {
    var result = exp1.toString
    if (args != null)
      result += "(" + args.mkString(",") + ")"
    result
  }

  override def toJson1 = {
    val funappl = new JSONObject()
    val theArgs = new JSONArray()
    funappl.put("type", "FunApplExp")
    funappl.put("exp1", exp1.toJson)
    for (a <- args) theArgs.put(a.toJson)
    funappl.put("args", theArgs)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("operand", operand)
    expression.put("type", "Expression")

    operand.put(exp1.toJson)
    for (arg <- args) operand.put(arg.toJson)

    expression
  }
}

case class IfExp(cond: Exp, trueBranch: Exp, falseBranch: Option[Exp]) extends Exp {
  override def toSMT: String = {
    val condSMT = cond.toSMT
    val trueSMT = trueBranch.toSMT
    val falseSMT = falseBranch match {
      case None          => "???"
      case Some(elseExp) => elseExp.toSMT
    }
    s"(ite $condSMT $trueSMT $falseSMT)"
  }

  override def toString = {
    var result = s"if $cond then\n"
    moveIn
    result += indent + s"$trueBranch\n"
    moveOut
    if (falseBranch.nonEmpty) {
      result += indent + "else\n"
      moveIn
      result += indent + falseBranch.get
      moveOut
    }
    result
  }

  override def toJson1 = {
    val expression = new JSONObject()
    expression.put("type", "IfExp")
    expression.put("cond", cond.toJson)
    expression.put("trueBranch", trueBranch.toJson)
    falseBranch match { case Some(fb) => expression.put("falseBranch", fb.toJson) case None => expression }
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "If"))
    operand.put(cond.toJson)
    operand.put(trueBranch.toJson)
    falseBranch match { case Some(fb) => operand.put(fb.toJson) case None => }

    expression
  }
}

case class MatchExp(exp: Exp, m: List[MatchCase]) extends Exp {
  override def toString = {
    var result = s"match $exp with\n"
    moveIn
    for (mtch <- m) {
      result += indent + s"$mtch\n"
    }
    moveOut
    result += indent + "}"
    result
  }

  override def toJson1 = {
    val matchexp = new JSONObject()
    val theCases = new JSONArray()
    matchexp.put("type", "MatchExp")
    for (c <- m) theCases.put(c.toJson)
    matchexp.put("exp", exp.toJson)
    matchexp.put("m", theCases)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "MatchExp"))
    operand.put(exp.toJson)
    for (mc <- m) operand.put(mc.toJson)

    expression
  }
}

// KH: How did this become an expression?

case class MatchCase(patterns: List[Pattern], exp: Exp) extends Exp {
  override def toString =
    "case " + patterns.mkString("|") + " => " + exp

  override def toJson1 = {
    val matchcase = new JSONObject()
    val thePatterns = new JSONArray()
    matchcase.put("type", "MatchCase")
    for (p <- patterns) thePatterns.put(p.toJson)
    matchcase.put("patterns", thePatterns)
    matchcase.put("exp", exp.toJson)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "MatchCase"))
    operand.put(exp.toJson)
    for (pattern <- patterns) operand.put(pattern.toJson)
    expression
  }
}

case class BlockExp(body: List[MemberDecl]) extends Exp {
  override def toSMT: String = 
    UtilSMT.memberList2SMT(body)
  
  override def toString = {
    var result = "{\n"
    moveIn
    for (member <- body) {
      result += indent + member + "\n"
    }
    moveOut
    result += indent + "}"
    result
  }

  override def toJson1 = {
    val expression = new JSONObject()
    expression.put("type", "BlockExp")
    val theBody = new JSONArray()
    for (member <- body) theBody.put(member.toJson)
    expression.put("body", theBody)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "BlockExp"))
    for (md <- body) operand.put(md.toJson)

    expression

  }
}

case class WhileExp(cond: Exp, body: Exp) extends Exp {
  override def toString = {
    var result = s"while $cond do"
    moveIn
    result += indent + body
    moveOut
    result
  }

  override def toJson1 = {
    val whileexp = new JSONObject()
    whileexp.put("type", "WhileExp")
    whileexp.put("condition", cond.toJson)
    whileexp.put("body", body.toJson)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "WhileExp"))
    operand.put(cond.toJson)
    operand.put(body.toJson)

    expression
  }
}

case class ForExp(pattern: Pattern, exp: Exp, body: Exp) extends Exp {
  override def toString = {
    var result = s"for $pattern in $exp do"
    moveIn
    result += indent + body
    moveOut
    result
  }

  override def toJson1 = {
    val forexp = new JSONObject()
    forexp.put("type", "ForExp")
    forexp.put("pattern", pattern.toJson)
    forexp.put("exp", exp.toJson)
    forexp.put("body", body.toJson)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("type", "Expression")
    expression.put("operand", operand)

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "ForExp"))
    operand.put(pattern.toJson)
    operand.put(exp.toJson)
    operand.put(body.toJson)

    expression
  }
}

case class BinExp(exp1: Exp, op: BinaryOp, exp2: Exp) extends Exp {
  override def toSMT: String = {
    val exp1SMT = exp1.toSMT
    val exp2SMT = exp2.toSMT
    if (UtilSMT.isConstructorPredicate(this)) {
      s"(= (deref $exp1SMT) $exp2SMT)"
    } else {
      op match {
        case NEQ =>
          s"(not (= $exp1SMT $exp2SMT))"
        case TUPLEINDEX =>
          assert(exp2.isInstanceOf[IntegerLiteral], "Tuple index must be an integer literal!")
          val indexFunSMT = s"_$exp2SMT"
          s"($indexFunSMT $exp1SMT)"
        case _ =>
          val opSMT = op.toSMT
          s"($opSMT $exp1SMT $exp2SMT)"
      }
    }
  }

  override def toString = s"$exp1 $op $exp2"

  override def toJson1 = {
    val expression = new JSONObject()
    expression.put("type", "BinExp")
    expression.put("op", op.toJsonName)
    expression.put("exp1", exp1.toJson)
    expression.put("exp2", exp2.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("operand", operand)
    expression.put("type", "Expression")

    operand.put(new JSONObject().put("type", "ElementValue").put("element", op.toJsonName))
    operand.put(exp1.toJson)
    operand.put(exp2.toJson)

    expression
  }
}

case class UnaryExp(op: UnaryOp, exp: Exp) extends Exp {
  override def toSMT: String = {
    val opSMT = op.toSMT
    val expSMT = exp.toSMT
    s"($opSMT $expSMT)"
  }

  override def toString =
    if (op == PREV)
      s"$exp$op"
    else
      s"$op$exp"

  override def toJson1 = {
    val expression = new JSONObject()

    expression.put("exp", exp.toJson)
    expression.put("type", "UnaryExp")
    expression.put("op", op.toJsonName)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    expression.put("operand", operand)
    expression.put("type", "Expression")

    operand.put(new JSONObject().put("type", "ElementValue").put("element", op.toJsonName))
    operand.put(exp.toJson)

    expression
  }
}

case class QuantifiedExp(quant: Quantifier,
                         bindings: List[RngBinding],
                         exp: Exp) extends Exp {

  override def toSMT: String = {
    val quantSMT = quant.toSMT
    val bindingsSMT = bindings.map(_.toSMT).mkString
    val expSMT = exp.toSMT
    val result = s"($quantSMT ($bindingsSMT) $expSMT)"
    result
  }

  override def toString = s"$quant ${bindings.mkString(",")} . $exp"

  override def toJson1 = {
    val expression = new JSONObject()
    val binding = new JSONArray()

    for (bind <- bindings) binding.put(bind.toJson)

    expression.put("quant", quant.toJson)
    expression.put("bindings", binding)
    expression.put("exp", exp.toJson)
    expression.put("type", "QuantifiedExp")
  }

  override def toJson2 = {

    val expression = new JSONObject()
    val operand = new JSONArray()
    val theBindings = new JSONArray()
    for (bind <- bindings) theBindings.put(bind.toJson)

    expression.put("operand", operand)
    expression.put("type", "Expression")

    operand.put(quant.toJson)
    //operand.append(theBindings);
    for (i <- 0 to theBindings.length() - 1) operand.put(theBindings.get(i))
    operand.put(exp.toJson)

    expression
  }
}

case class TupleExp(exps: List[Exp]) extends Exp {
  override def toSMT: String = {
    val constrSMT = s"mk-Tuple${exps.length}"
    val expsSMT = exps.map(_.toSMT).mkString(" ")
    s"($constrSMT $expsSMT)"
  }

  override def toString = "Tuple(" + exps.mkString(",") + ")"

  override def toJson1 = {
    val tupleExp = new JSONObject()
    val expressions = new JSONArray()
    for (exp <- exps) expressions.put(exp.toJson)
    tupleExp.put("type", "TupleExp")
    tupleExp.put("exps", expressions)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Tuple"))
    for (exp <- exps) operand.put(exp.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

trait CollectionKind {
  def toJson: String
}

case object SetKind extends CollectionKind {
  override def toString = "Set"
  override def toJson = toString
}

case object SeqKind extends CollectionKind {
  override def toString = "Seq"
  override def toJson = toString
}

case object BagKind extends CollectionKind {
  override def toString = "Bag"
  override def toJson = toString
}

case class CollectionEnumExp(kind: CollectionKind, exps: List[Exp]) extends Exp {
  override def toString = kind + "{" + exps.mkString(",") + "}"

  override def toJson1 = {
    val enumExp = new JSONObject()
    val expressions = new JSONArray()
    for (exp <- exps) expressions.put(exp.toJson)
    enumExp.put("type", "CollectionEnumExp")
    enumExp.put("exps", expressions)
    enumExp.put("kind", kind.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", kind))
    for (exp <- exps) operand.put(exp.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class CollectionRangeExp(kind: CollectionKind, exp1: Exp, exp2: Exp) extends Exp {
  override def toString = s"$kind{$exp1 .. $exp2}"

  override def toJson1 = {
    val rangeExp = new JSONObject()
    rangeExp.put("type", "CollectionRangeExp")
    rangeExp.put("kind", kind.toJson)
    rangeExp.put("exp1", exp1.toJson)
    rangeExp.put("exp2", exp2.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", kind))
    operand.put(exp1.toJson)
    operand.put(exp2.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class CollectionComprExp(kind: CollectionKind,
                              exp1: Exp,
                              bindings: List[RngBinding],
                              exp2: Exp) extends Exp {

  override def toString = s"$kind{$exp1 | ${bindings.mkString(",")} . $exp2}"

  override def toJson1 = {
    val comprExp = new JSONObject()
    val bndgs = new JSONArray()
    for (binding <- bindings) bndgs.put(binding.toJson)
    comprExp.put("type", "CollectionComprExp")
    comprExp.put("kind", kind.toJson)
    comprExp.put("exp1", exp1.toJson)
    comprExp.put("bindings", bndgs)
    comprExp.put("exp2", exp2.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", kind))
    operand.put(exp1.toJson)
    operand.put(exp2.toJson)
    for (binding <- bindings) operand.put(binding.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class LambdaExp(pat: Pattern, exp: Exp) extends Exp {
  override def toString = {
    s"$pat -> $exp"
  }

  override def toJson1 = {
    val lambdaExp = new JSONObject()
    lambdaExp.put("type", "LambdaExp")
    lambdaExp.put("pat", pat.toJson)
    lambdaExp.put("exp", exp.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Lambda"))
    operand.put(pat.toJson)
    operand.put(exp.toJson)

    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class AssertExp(exp: Exp) extends Exp {
  override def toString = s"assert($exp)"

  override def toJson1 = {
    new JSONObject().put("type", "AssertExp").put("exp", exp.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Assert"))
    operand.put(exp.toJson)

    expression.put("type", "Expression")
    expression.put("operand", operand)

  }
}

case class TypeCastCheckExp(cast: Boolean, exp: Exp, ty: Type) extends Exp {
  override def toSMT: String = {
    if (cast)
      UtilSMT.error(s"type cast $this")
    ty match {
      case IdentType(QualifiedName(name :: Nil),Nil) =>
        val expSMT = exp.toSMT
        s"(deref-isa-$name $expSMT)"
      case _ =>
        UtilSMT.error(s"type test format $this")
    }
  }
  
  override def toString =
    if (cast) s"$exp as $ty"
    else s"$exp is $ty"

  override def toJson1 = {
    val typecastcheckexp = new JSONObject()
    typecastcheckexp.put("type", "TypeCastCheckExp")
    typecastcheckexp.put("cast", cast.toString)
    typecastcheckexp.put("exp", exp.toJson)
    typecastcheckexp.put("ty", ty.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "typeCastCheck"))
    operand.put(cast.toString)
    operand.put(exp.toJson)
    operand.put(ty.toJson)

    expression.put("type", "Expression")
    expression.put("operand", operand)

  }
}

case class ReturnExp(exp: Exp) extends Exp {
  override def toSMT: String = {
    exp.toSMT
  }

  override def toString = s"return $exp"

  override def toJson1 = {
    val returnexp = new JSONObject()
    returnexp.put("type", "ReturnExp")
    returnexp.put("exp", exp.toJson)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Return"))
    operand.put(exp.toJson)

    expression.put("type", "Expression")
    expression.put("operand", operand)

  }
}

case object BreakExp extends Exp {
  override def toString = "break"

  override def toJson1 =
    new JSONObject().put("type", "BreakExp")

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", toString))

    expression.put("type", "Expression")
    expression.put("operand", operand)
  }

}

case object ContinueExp extends Exp {
  override def toString = "continue"

  override def toJson1 =
    new JSONObject().put("type", "ContinueExp")

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", toString))

    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case object ResultExp extends Exp {
  override def toString = "result"

  override def toJson1 = {
    new JSONObject().put("type", "ResultExp")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", toString))

    expression.put("type", "Expression")
    expression.put("operand", operand)
  }

}

case object StarExp extends Exp {
  override def toString = "*"

  override def toJson1 = {
    new JSONObject().put("type", "StarExp")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()

    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Star"))

    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

// KH: How come this became an expression?:

trait Argument extends Exp

case class PositionalArgument(exp: Exp) extends Argument {
  override def toSMT: String = exp.toSMT

  override def toString = exp.toString

  override def toJson1 = {
    val positionalArgument = new JSONObject()
    positionalArgument.put("type", "PositionalArgument")
    positionalArgument.put("exp", exp.toJson)
  }

  override def toJson2 = {
    exp.toJson
  }
}

case class NamedArgument(ident: String, exp: Exp) extends Argument {
  override def toString = s"$ident :: $exp"

  override def toJson1 = {
    val classArgument = new JSONObject()
    classArgument.put("type", "NamedArgument")
    classArgument.put("ident", ident)
    classArgument.put("exp", exp.toJson)
  }

  // FIXME -- missing support for named arguments
  override def toJson2 = {
    exp.toJson
  }
}

trait BinaryOp {
  def toSMT: String
  def toJsonName: String
}

case object LT extends BinaryOp {
  def toSMT = "<"

  override def toString = "<"

  override def toJsonName = "LT"
}

case object LTE extends BinaryOp {
  def toSMT = "<="

  override def toString = "<="

  override def toJsonName = "LTE"
}

case object GT extends BinaryOp {
  def toSMT = ">"

  override def toString = ">"

  override def toJsonName = "GT"
}

case object GTE extends BinaryOp {
  def toSMT = ">="

  override def toString = ">="

  override def toJsonName = "GTE"
}

case object AND extends BinaryOp {
  def toSMT = "and"

  override def toString = "&&"

  override def toJsonName = "And"
}

case object OR extends BinaryOp {
  def toSMT = "or"

  override def toString = "||"

  override def toJsonName = "OR"
}

case object IMPL extends BinaryOp {
  def toSMT = "=>"

  override def toString = "=>"

  override def toJsonName = "Implies"
}

case object IFF extends BinaryOp {
  def toSMT = "="

  override def toString = "<=>"

  override def toJsonName = "Iff"
}

case object EQ extends BinaryOp {
  def toSMT = "="

  override def toString = "="

  override def toJsonName = "EQ"
}

case object NEQ extends BinaryOp {
  def toSMT = ???

  override def toString = "!="

  override def toJsonName = "NotEQ"
}

case object MUL extends BinaryOp {
  def toSMT = "*"

  override def toString = "*"

  override def toJsonName = "Times"
}

case object DIV extends BinaryOp {
  def toSMT = "/"

  override def toString = "/"

  override def toJsonName = "Divide"
}

case object REM extends BinaryOp {
  def toSMT = ???

  override def toString = "%"

  override def toJsonName = "Modulo"
}

case object SETINTER extends BinaryOp {
  def toSMT = ???

  override def toString = "inter"

  override def toJsonName = "Inter"
}

case object SETDIFF extends BinaryOp {
  def toSMT = ???

  override def toString = "\\"

  override def toJsonName = "SetDiff"
}

case object LISTCONCAT extends BinaryOp {
  def toSMT = ???

  override def toString = "^"

  override def toJsonName = "Concat"
}

case object TUPLEINDEX extends BinaryOp {
  def toSMT = ???

  override def toString = "#"

  override def toJsonName = "TupleIndex"
}

case object ADD extends BinaryOp {
  def toSMT = "+"

  override def toString = "+"

  override def toJsonName = "Plus"
}

case object SUB extends BinaryOp {
  def toSMT = "-"

  override def toString = "-"

  override def toJsonName = "Minus"
}

case object SETUNION extends BinaryOp {
  def toSMT = ???

  override def toString = "union"

  override def toJsonName = "Union"
}

case object ISIN extends BinaryOp {
  def toSMT = ???

  override def toString = "isin"

  override def toJsonName = "IsIn"
}

case object NOTISIN extends BinaryOp {
  def toSMT = ???

  override def toString = "!isin"

  override def toJsonName = "NotIn"
}

case object SUBSET extends BinaryOp {
  def toSMT = ???

  override def toString = "subset"

  override def toJsonName = "Subset"
}

case object PSUBSET extends BinaryOp {
  def toSMT = ???

  override def toString = "psubset"

  override def toJsonName = "PSubset"
}

case object ASSIGN extends BinaryOp {
  def toSMT = ???

  override def toString = ":="

  override def toJsonName = "Assign"
}

trait UnaryOp {
  def toSMT: String = ???

  def toJsonName: String // why is it called toJsonName and not toJson?
}

case object NOT extends UnaryOp {
  override def toSMT = "not"

  override def toString = "!"

  override def toJsonName = "Not"
}

case object NEG extends UnaryOp {
  override def toSMT = "-"

  override def toString = "-"

  override def toJsonName = "Neg"
}

case object PREV extends UnaryOp {
  override def toString = "~"
  override def toJsonName = "Prev"
}

trait Literal extends Exp

case class IntegerLiteral(i: Int) extends Literal {
  override def toSMT: String = {
    i.toString
  }

  override def toString = i.toString

  override def toJson1 = {
    val o = new JSONObject()
    o.put("i", i)
    o.put("type", "LiteralInteger")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    expression.put("type", "LiteralInteger").put("integer", i)
  }
}

case class RealLiteral(f: java.math.BigDecimal) extends Literal {
  override def toSMT: String = {
    f.formatted("%.16f")
  }

  override def toString = f.toString

  override def toJson1 = {
    val o = new JSONObject()
    o.put("f", f.formatted("%.16f"))
    o.put("type", "LiteralFloatingPoint")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    expression.put("type", "LiteralReal").put("double", f)
  }
}

case class CharacterLiteral(c: Char) extends Literal {
  override def toString = c.toString

  override def toJson1 = {
    val o = new JSONObject()
    o.put("c", c)
    o.put("type", "LiteralCharacter")
  }

  override def toJson2 = {
    // Warning: loses information when treating simply as a String
    val expression = new JSONObject()
    expression.put("type", "LiteralString").put("string", toString)
  }
}

case class StringLiteral(s: String) extends Literal {
  override def toString = s

  override def toJson1 = {
    val o = new JSONObject()
    o.put("string", s.replaceAll("\"", ""))
    o.put("type", "StringLiteral")
  }

  override def toJson2 = {
    val value = new JSONObject()
    value.put("type", "LiteralString").put("string", toString.replaceAll("\"", ""))
  }
}

case class BooleanLiteral(b: Boolean) extends Literal {
  override def toSMT: String = b.toString

  override def toString = b.toString

  override def toJson1 = {
    val o = new JSONObject()
    o.put("b", b)
    o.put("type", "LiteralBoolean")
  }

  override def toJson2 = {
    val value = new JSONObject()
    value.put("type", "LiteralBoolean").put("boolean", toString)
  }
}

case object NullLiteral extends Literal {
  override def toString = "null"

  override def toJson1 = {
    new JSONObject().put("type", "NullLiteral")
  }

  override def toJson2 = {
    val value = new JSONObject()
    value.put("type", "LiteralNull")
  }
}

case object ThisLiteral extends Literal {
  override def toSMT = "this"

  override def toString = "this"

  override def toJson1 = {
    new JSONObject().put("type", "ThisLiteral")
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "This"))
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

trait Quantifier {
  def toSMT: String

  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }

  def toJson1: JSONObject
  def toJson2: JSONObject
}

case object Forall extends Quantifier {
  def toSMT = "forall"

  override def toString = "forall"

  override def toJson1 = {
    val o = new JSONObject()
    o.put("element", "Forall")
    o.put("type", "Quantifier")
    // o.put("type", "Forall") -- should it not be this?
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "ForAll")
  }
}

case object Exists extends Quantifier {
  def toSMT = "exists"

  override def toString = "exists"

  override def toJson1 = {
    val o = new JSONObject()
    o.put("element", "Exists")
    o.put("type", "Quantifier")
    // o.put("type", "Exists") -- should it not be this?
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "Exists")
  }
}

trait Type {
  def toSMT: String = ???
  def toJson: JSONObject = {
    if (ASTOptions.useJson1 == true) toJson1
    else toJson2
  }

  def toJson1: JSONObject
  def toJson2: JSONObject = {
    // FIXME -- Should use an identifier here instead of type name, right?
    new JSONObject().put("type", "ElementValue").put("element", toString)
  }
}

case class CollectType(ty: List[Type]) extends PrimitiveType {
  override def toJson1 = null
  override def toJson2 = null
}

case class SumType(ty: List[Type]) extends PrimitiveType {
  override def toJson1 = null
  override def toJson2 = null
}

case object AnyType extends Type {
  override def toJson1 = null
  override def toJson2 = null
}

case class ClassType(ident: QualifiedName) extends Type {
  override def toString = s"ClassOf($ident)"
  override def toSMT: String = ???
  override def toJson1 = {
    val identType = new JSONObject()
    val arguments = new JSONArray()
    identType.put("ident", ident.toJson)
    identType.put("type", "ClassType")
  }

}

case class IdentType(ident: QualifiedName, args: List[Type]) extends Type {
  override def toSMT: String = "Ref"

  override def toString =
    if (args == null || args.isEmpty)
      ident.toString
    else s"$ident[${args.mkString(",")}]"

  override def toJson1 = {
    val identType = new JSONObject()
    val arguments = new JSONArray()
    for (arg <- args) arguments.put(arg.toJson)
    identType.put("args", arguments)
    identType.put("ident", ident.toJson)
    identType.put("type", "IdentType")
  }

}

case class CartesianType(types: List[Type]) extends Type {
  // Probably needs to be a Ref.
  override def toSMT: String = {
    val typesSMT = types.map(_.toSMT).mkString(" ")
    val typeSMT = s"Tuple${types.length}"
    s"($typeSMT $typesSMT)"
  }

  override def toString = types.mkString(" * ")

  override def toJson1 = {
    val cartesianType = new JSONObject()
    val theTypes = new JSONArray()
    for (ty <- types) theTypes.put(ty.toJson)
    cartesianType.put("types", theTypes)
    cartesianType.put("type", "CartesianType")
  }

  // FIXME -- See Type trait
  override def toJson2 = {
    val typeName = "Tuple<" + types.mkString(",") + ">"
    new JSONObject().put("type", "ElementValue").put("element", typeName )
  }

}

case class FunctionType(from: Type, to: Type) extends Type {
  override def toString = s"$from -> $to"

  override def toJson1 = {
    val functionType = new JSONObject()
    functionType.put("from", from.toJson)
    functionType.put("to", to.toJson)
    functionType.put("type", "FunctionType")
  }

  // FIXME -- See Type trait
  override def toJson2 = {
    val typeName = "Map<" + from.toString + ","  + to.toString + ">"
    new JSONObject().put("type", "ElementValue").put("element", typeName )
  }
}

case class ParenType(ty: Type) extends Type {
  override def toSMT: String = (ty.toSMT)

  override def toString = s"($ty)"

  override def toJson1 = {
    val parenType = new JSONObject()
    parenType.put("type", "ParenType")
    parenType.put("ty", ty.toJson)
  }

  // FIXME -- See Type trait
  override def toJson2 = {
    val typeName = "List<" + ty.toString + ">"
    new JSONObject().put("type", "ElementValue").put("element", typeName )
  }
}

case class SubType(ident: String, ty: Type, exp: Exp) extends Type {
  override def toString = s"{| $ident : $ty . $exp |}"

  override def toJson1 = {
    val subType = new JSONObject()
    subType.put("ident", ident)
    subType.put("exp", exp.toJson)
    subType.put("t", ty.toJson)
    subType.put("type", "SubType")
  }

  // FIXME -- See Type trait
  override def toJson2 = {
    val typeName = "Set<" + ty.toString + ">"
    new JSONObject().put("type", "ElementValue").put("element", typeName )
  }
}

trait PrimitiveType extends Type

case object BoolType extends PrimitiveType {
  override def toSMT: String = "Bool"

  override def toString = "Bool"

  override def toJson1 = {
    new JSONObject().put("type", "BoolType")
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "Boolean" )
  }
}

case object CharType extends PrimitiveType {
  override def toString = "Char"

  override def toJson1 = {
    new JSONObject().put("type", "CharType")
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "Character" )
  }
}

case object IntType extends PrimitiveType {
  override def toSMT: String = "Int"

  override def toString = "Int"

  override def toJson1 = {
    new JSONObject().put("type", "IntType")
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "Integer" )
  }
}

case object RealType extends PrimitiveType {
  override def toSMT: String = "Real"

  override def toString = "Real"

  override def toJson1 = {
    new JSONObject().put("type", "RealType")
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "Double" )
  }
}

case object StringType extends PrimitiveType {
  override def toString = "String"

  override def toJson1 = {
    new JSONObject().put("type", "StringType")
  }
}

case object UnitType extends PrimitiveType {
  override def toString = "Unit"

  override def toJson1 = {
    new JSONObject().put("type", "UnitType")
  }
}

trait Pattern {
  def boundNames: Set[String] = Set()
  def toSMT: String = ???
  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }

  def toJson1: JSONObject
  def toJson2: JSONObject
}

case class LiteralPattern(literal: Literal) extends Pattern {
  override def toString = literal.toString

  override def toJson1 = {
    new JSONObject().put("type", "LiteralPattern").put("literal", literal.toJson)
  }

  override def toJson2 = {
    literal.toJson2
  }
}

case class IdentPattern(ident: String) extends Pattern {
  override def boundNames = Set(ident)

  override def toSMT = ident

  override def toString = ident

  override def toJson1 = {
    val identPattern = new JSONObject()
    identPattern.put("ident", ident)
    identPattern.put("type", "IdentPattern")
  }

  override def toJson2 = {
    new JSONObject().put("type", "LiteralString").put("string", ident)
  }
}

case class ProductPattern(patterns: List[Pattern]) extends Pattern {
  override def boundNames = patterns.map(_.boundNames).toSet.flatten

  override def toString = "(" + patterns.mkString(",") + ")"

  override def toJson1 = {
    val productPattern = new JSONObject()
    val thepatterns = new JSONArray()
    for (pattern <- patterns) thepatterns.put(pattern.toJson)
    productPattern.put("type", "ProductPattern")
    productPattern.put("patterns", patterns)
  }

  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    
    // Warning - assuming newList is understandable by consumer of JSON 
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "newList"))
    for (p <- patterns) operand.put(p.toJson)

    expression.put("type", "Expression")
    expression.put("operand", operand)

  }
}

case class TypedPattern(pattern: Pattern, ty: Type) extends Pattern {
  override def boundNames = pattern.boundNames

  override def toString = s"$pattern : $ty"

  override def toJson1 =
    new JSONObject()
      .put("type", "TypedPattern")
      .put("pattern", pattern.toJson)
      .put("ty", ty.toJson)

  override def toJson2 = {
    pattern.toJson
  }
}

case object DontCarePattern extends Pattern {
  override def toString = "_"

  override def toJson1 = {
    new JSONObject().put("type", "DontCarePattern")
  }

  override def toJson2 = {
    new JSONObject().put("type", "ElementValue").put("element", "DontCare")
  }
}

case class RngBinding(patterns: List[Pattern], collection: Collection) {
  def toSMT: String = {
    val patternIds = patterns.map(_.toSMT)
    val typeSMT = collection.toSMT
    patternIds.map(id => s"($id $typeSMT)").mkString
  }

  def boundNames: Set[String] = {
    patterns.map(_.boundNames).toSet.flatten
  }

  override def toString = patterns.mkString(",") + " : " + collection

  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }

  def toJson1 = {
    val binding = new JSONObject()
    val thePatterns = new JSONArray()
    for (pattern <- patterns) thePatterns.put(pattern.toJson)
    binding.put("patterns", thePatterns)
    binding.put("collection", collection.toJson)
    binding.put("type", "RngBinding")
  }

  // FIXME
  def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "RngBinding"))
    operand.put(collection.toJson)
    for (p <- patterns) operand.put(p.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

trait Collection {
  def toSMT: String = ???

  def toJson: JSONObject = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }

  def toJson1: JSONObject
  def toJson2: JSONObject
}

case class ExpCollection(exp: Exp) extends Collection {
  override def toString = exp.toString()

  override def toJson1 = {
    val collection = new JSONObject()
    collection.put("type", "ExpCollection")
    collection.put("exp", exp.toJson)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "ExpCollection"))
    operand.put(exp.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class TypeCollection(ty: Type) extends Collection {
  override def toSMT = ty.toSMT

  override def toString = ty.toString()

  override def toJson1 = {
    val collection = new JSONObject()
    collection.put("type", "TypeCollection")
    collection.put("ty", ty.toJson)
  }

  // FIXME
  override def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "TypeCollection"))
    operand.put(ty.toJson)
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}

case class Multiplicity(exp1: Exp, exp2: Option[Exp]) {
  override def toString =
    if (exp2.nonEmpty)
      s"[$exp1..${exp2.get}]"
    else
      s"[$exp1]"

  def toJson = {
    if (ASTOptions.useJson1) toJson1
    else toJson2
  }

  def toJson1 = {
    val multiplicity = new JSONObject()
    multiplicity.put("type", "Multiplicity")
    multiplicity.put("exp1", exp1.toJson)
    exp2 match { case Some(e) => multiplicity.put("exp2", e.toJson) case None => multiplicity }
  }

  // FIXME
  def toJson2 = {
    val expression = new JSONObject()
    val operand = new JSONArray()
    operand.put(new JSONObject().put("type", "ElementValue").put("element", "Multiplicity"))
    operand.put(exp1.toJson)
    exp2 match { case Some(e) => operand.put(e.toJson) case None => }
    expression.put("type", "Expression")
    expression.put("operand", operand)
  }
}
