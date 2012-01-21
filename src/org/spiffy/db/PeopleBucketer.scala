package org.spiffy.db

import org.scalagfx.io.Path
import org.scalagfx.math.{Pos2d, Pos3d, Index3i, Frame2d, Scalar}
import org.scalagfx.houdini.geo.GeoWriter
import org.scalagfx.houdini.geo.attr.{PrimitiveIntAttr}

import collection.mutable.HashMap
import collection.immutable.{ TreeMap, TreeSet }

import java.sql.{ Connection, DriverManager, ResultSet, SQLException, Timestamp }
import java.util.{ Calendar, Date, GregorianCalendar }
import java.io.{ BufferedWriter, FileWriter }

object PeopleBucketer {
  
  //---------------------------------------------------------------------------------------------------------
  //   C L A S S E S 
  //---------------------------------------------------------------------------------------------------------

  /** A counter of e-mails sent and received by each person at each point in time. */
  class MailBucket
  {
    /** Internal storage for counters: stamp -> pid -> activity */
    private var table = new HashMap[Long,HashMap[Long,Activity]]
    
    /** Increment the e-mail counter.
      * @param stamp The time stamp of start of period (in UTC milliseconds).
      * @param sendID The unique identifier of the person who sent the e-mail (people.personid).
      * @param recvID The unique identifier of the person who received the e-mail (people.personid). */
    def inc(stamp: Long, sendID: Long, recvID: Long) {
      val m = table.getOrElseUpdate(stamp, new HashMap[Long,Activity])
      m += (sendID -> m.getOrElseUpdate(sendID, Activity()).incSend)
      m += (recvID -> m.getOrElseUpdate(sendID, Activity()).incRecv)
    }
    
    /** The range of times stored. 
      * @return The first and last period time stamps (in UTC milliseconds). */
    def timeRange: (Long, Long) = 
      ((Long.MaxValue, 0L) /: table.keys) { 
        case ((first, last), stamp) => (first min stamp, last max stamp) 
      }
    
    /** The total number of e-mails during the given period.
      * @param stamp The time stamp of start of period (in UTC milliseconds). */
    def totalPeriodActivity(stamp: Long): Activity = {
      if(!table.contains(stamp)) Activity()
      else (Activity() /: table(stamp).values)(_ + _)
    }
    
    /** Get the total e-mail activity of each person sorted by most to least active. */
    def totalPersonalActivity: TreeSet[PersonalActivity] = {
      var totals = new HashMap[Long,Activity]
      for(m <- table.values) {
        for((pid, act) <- m) {
          totals += (pid -> (totals.getOrElseUpdate(pid, Activity()) + act)) 
        }
      }
      ((new TreeSet[PersonalActivity]) /: totals) { 
        case (rtn, (pid, act)) => rtn + PersonalActivity(pid, act)
      } 
    }
    
    /** Get the e-mail activity history of a given person for each time period. */
    def personalActivity(pid: Long): Array[Activity] = {
      val stamps = new TreeSet[Long] ++ table.keySet
      val rtn = new Array[Activity](stamps.size)
      var i = 0
      for(stamp <- stamps) { 
        rtn(i) = if(!table.contains(stamp)) Activity()
        		 else table(stamp).getOrElse(pid, Activity())
        i = i + 1
      }
      rtn
    }
  }
  
  /**
   * Some e-mail activity. 
   * @constructor
   * @param sent The number of messages sent.
   * @param recv The number of message received.
   */
  class Activity protected (val sent: Long, val recv: Long)
  {
    /** The total number e-mails sent and received by the person. */
    def total = sent + recv

    /** Accumulate message counts. */
    def + (that: Activity) = Activity(sent+that.sent, recv+that.recv)
        
    /** Increment to the sent count. */
    def incSend = Activity(sent+1, recv)
    
    /** Increment to the received count. */
    def incRecv = Activity(sent, recv+1)
    
    override def toString = "Activity(sent=" + sent + ", recv=" + recv + ")"
  }
  
  object Activity 
  {
    def apply() = new Activity(0L, 0L)
    def apply(sent: Long, recv: Long) = new Activity(sent, recv)
  }
  
  /**
   * The e-mail activity of a person. 
   * @constructor
   * @param pid The unique identifier (people.personid).
   * @param sent The number of messages sent.
   * @param recv The number of message received.
   */
  class PersonalActivity private (val pid: Long, sent: Long, recv: Long)
    extends Activity(sent, recv)
    with Ordered[PersonalActivity] 
  {
	/** Ordered in descending total number of e-mails and ascending IDs. */
    def compare(that: PersonalActivity): Int = 
      (that.total compare total) match {
        case 0 => pid compare that.pid
        case c => c
      }

    override def toString = "PersonalActivity(pid=" + pid + ", sent=" + sent + ", recv=" + recv + ")"
  }

  object PersonalActivity 
  {
    def apply(pid: Long) = new PersonalActivity(pid, 0L, 0L)
    def apply(pid: Long, act: Activity) = new PersonalActivity(pid, act.sent, act.recv)
    def apply(pid: Long, sent: Long, recv: Long) = new PersonalActivity(pid, sent, recv)
  }
  
  /** A person. 
   * @constructor
   * @param pid The personal identifier (people.personid).
   * @param unified The unified personal identifier.  Same as (pid) if there is only one record for this person
   * but points to the primary ID if there are duplicates. 
   * @param name Canonicalized personal name: real name or e-mail prefix derived. */
  class Person private (val pid: Long, val unified: Long, val name: String) {
    override def toString = "Person(pid=" + pid + ", unified=" + unified + ", name=\"" + name + "\")"
  }
  
  object Person 
  {
    def apply(pid: Long, name: String) = new Person(pid, pid, name)
    def apply(pid: Long, unified: Long, name: String) = new Person(pid, unified, name)
  }

    
  //---------------------------------------------------------------------------------------------------------
  //   M A I N    
  //---------------------------------------------------------------------------------------------------------

  // Loads the JDBC driver. 
  classOf[com.mysql.jdbc.Driver]
  
  /** Top level method. */
  def main(args: Array[String]) {
    val outdir = Path("./artwork/houdini/geo")
    
    try {
      val cal = new GregorianCalendar
      val connStr = "jdbc:mysql://localhost:3306/enron?user=enron&password=slimyfucks"
      val conn = DriverManager.getConnection(connStr)
      try {
        println("Determining the Active Interval...")
        val range @ (firstMonth, lastMonth) = {
          val threshold = 10000
          val perMonth = activeMonths(conn, threshold)

          val path = Path("./data/stats/activeMonths.csv")
          println("  Writing: " + path)
          val out = new BufferedWriter(new FileWriter(path.toFile))
          try {
            for((ms, cnt) <- perMonth) 
              out.write(ms + "," + cnt + ",\n")
          }
          finally {
            out.close
          }
          
          print("  Activity per Month: ")
          for((ms, cnt) <- perMonth) {
            cal.setTimeInMillis(ms)
            print((cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.YEAR) + "=" + cnt + " ")
          }
          println
          
          (perMonth.firstKey, perMonth.lastKey)
        }
        
        //---------------------------------------------------------------------------------------------------

        println
        println("Collecting People...")
        val people = {
          val ps = collectPeople(conn)

          val path = Path("./data/stats/people.csv")
          println("  Writing: " + path)
          val out = new BufferedWriter(new FileWriter(path.toFile))
          try {
            for ((_, p) <- ps)
              out.write(p.pid + "," + p.unified + "," + p.name + ",\n")
          }
          finally {
            out.close
          }

          ps
        }
        
        //---------------------------------------------------------------------------------------------------
        
        // ...

        
      } finally {
        conn.close
      }
    } catch {
      case ex =>
        println("Uncaught Exception: " + ex.getMessage + "\n" +
          "Stack Trace:\n" + ex.getStackTraceString)
    }
  }

  
  //-----------------------------------------------------------------------------------------------------------------------------------
  //   D A T A B A S E 
  //-----------------------------------------------------------------------------------------------------------------------------------

  /**
   * Get the number of e-mails received per month (index in UTC milliseconds), ignoring those months with
   * less activity than the given threshold number of emails.
   * @param conn The SQL connection.
   * @param threshold The minimum amount of e-mail activity.
   */
  def activeMonths(conn: Connection, threshold: Long): TreeMap[Long, Long] = {
    val cal = new GregorianCalendar
    var perMonth = new HashMap[Long, Long]
    val st = conn.createStatement
    val rs = st.executeQuery(
      "SELECT messagedt FROM recipients, messages " +
        "WHERE recipients.messageid = messages.messageid")
    while (rs.next) {
      try {
        val ts = rs.getTimestamp(1)

        cal.setTime(ts)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val ms = cal.getTimeInMillis

        perMonth += ms -> (perMonth.getOrElse(ms, 0L) + 1L)
      } catch {
        case _: SQLException => // Ignore invalid time stamps.
      }
    }

    (new TreeMap[Long, Long] /: perMonth)((rtn, e) => rtn + e)
      .filter(_ match { case (_, cnt) => cnt > threshold })
  }

  
  /**
   * Lookup the names of all the users, discarding those without valid names. 
   * @param conn The SQL connection. */
  def collectPeople(conn: Connection): TreeMap[Long, Person] = {
    var rtn = new TreeMap[Long, Person]

    val nameToID = new HashMap[String,Long]
    
    val st = conn.createStatement
    val rs = st.executeQuery("SELECT personid, email, name FROM people")
    while (rs.next) {
      try {
        val pid  = rs.getInt(1).toLong
        val addr = rs.getString(2)
        val nm   = rs.getString(3)

        val (prefix, domain) =
          if (addr == null) ("unknown", "unknown")
          else {
            addr.filter(_ != '"').filter(_ != ''').split("@") match {
              case Array(p, d) => (p, d)
              case _ => (addr, "unknown")
            }
          }

        val name =
          if (nm != null) {
            val n = nm.filter(_ != '"')
            if (n.size > 0) n else prefix
          } else prefix
          
        // Toss out bogus addresses.
        (name, domain) match {
          case ("e-mail", "enron.com") =>
          case ("unknown", _) | (_, "unknown") =>
          case _ => {
            val canon = name.toUpperCase.replace('.', ' ').replaceAll("  ", " ").trim
            if(canon.size > 0) {
              val person = 
                if(nameToID.contains(canon)) Person(pid, nameToID(canon), canon)
                else {
                  nameToID(canon) = pid
                  Person(pid, canon)
                } 
              rtn = rtn + (pid -> person)
            }
          }
        }
      } catch {
        case _: SQLException => // Ignore invalid people.
      }
    }

    rtn
  }

}