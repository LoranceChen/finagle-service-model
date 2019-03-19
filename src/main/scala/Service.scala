import scala.concurrent.Future
import scala.util.Random
import scala.util.control.NonFatal

/**
  * Service same as finagle's Service
  */
trait Service[-Req, +Rsp] extends (Req => Future[Rsp]) {
  lazy val name = "new Service: " + Random.nextInt
  println(s"${System.currentTimeMillis()} - ${getClass.getName} new service construct - ${name}")

}

trait Filter[-ReqIn, +RspOut, +ReqOut, -RspIn] extends ((ReqIn, Service[ReqOut, RspIn]) => Future[RspOut]) {
  lazy val name = "new Filter:" + Random.nextInt
  println(s"${System.currentTimeMillis()} - ${getClass.getName} new filter construct - ${name}")

  /**
    * @return
    *           (* Service *)
    * [ReqIn -> (Req2 -> Rep2) -> RepOut]
    *
    */
  def andThen[Req2, Rep2](next: Filter[ReqOut, RspIn, Req2, Rep2]) = new Filter[ReqIn, RspOut, Req2, Rep2] {
    Thread.sleep(1000)
    println(s"${System.currentTimeMillis()} - ${getClass.getName} filter construct - ${this.name} - ${Filter.this.name}")
    override def apply(request: ReqIn, module: Service[Req2, Rep2]) = {
      Thread.sleep(1000)
      println(s"${System.currentTimeMillis()} - ${getClass.getName} inner andthen apply - ${this.name} - ${Filter.this.name}")
      val mdl: Service[ReqOut, RspIn] = new Service[ReqOut, RspIn] {
        override def apply(v1: ReqOut) = {
          try {
            Thread.sleep(1000)
            println(s"${System.currentTimeMillis()} - ${getClass.getName} andthen service apply - ${this.name} - ${Filter.this.name}")
            next(v1, module)
          } catch {
            case NonFatal(e) => Future.failed(e)
          }
        }
      }
      Filter.this.apply(request, {
        Thread.sleep(1000)
        println(s"${System.currentTimeMillis()} - ${Filter.this.getClass.getName} andThen apply - ${this.name} - ${Filter.this.name}")
        mdl
      })
    }
  }

  def andThen(module: Service[ReqOut, RspIn]): Service[ReqIn, RspOut] = {
    new Service[ReqIn, RspOut] {
      Thread.sleep(1000)
      println(s"${System.currentTimeMillis()} - ${getClass.getName} service construct - ${this.name}")
      def apply(request: ReqIn) = Filter.this.apply(request, module)
    }
  }
}
