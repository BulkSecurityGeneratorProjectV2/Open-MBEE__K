package examples.borges

class <event> Event {
  t1 : Real
  t2 : Real  
}

event A
event B
event Telecom
event RadioScience
event TargetSlew

class Schedule {    

  req Schedule1OrSchedule2: 
    (
      A.t2 = B.t1 && -- encoding m
      B.t2 = Telecom.t1 && -- encodig m
      Telecom.t2 = RadioScience.t1 && -- encoding m
      RadioScience.t2 = TargetSlew.t1 -- encoding m
    )
    ||
    (
      A.t2 = B.t1 && -- encoding m
      B.t2 = Telecom.t1 && -- encoding m
      Telecom.t2 = RadioScience.t1 && -- encoding m 
      RadioScience.t1 = TargetSlew.t2 -- encoding M
    )
    
    req Distinctness:
      A.t2 > A.t1 &&
      B.t2 > B.t1 &&
      Telecom.t2 > Telecom.t1 &&
      RadioScience.t2 > RadioScience.t1 &&
      TargetSlew.t2 > TargetSlew.t1 &&
      A.t1 > 0 && B.t1 > 0 && Telecom.t1 > 0 && RadioScience.t1 > 0
      && TargetSlew.t1 > 0
    
}