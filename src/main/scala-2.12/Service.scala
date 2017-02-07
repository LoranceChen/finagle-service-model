import java.util.concurrent.TimeUnit

import scala.concurrent.{Future, Promise, blocking}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Module same as finagle's Service
  */
trait Module[-Req, +Rsp] extends (Req => Future[Rsp])

trait Filter[-ReqIn, +RepOut, +ReqOut, -RepIn] extends ((ReqIn, Module[ReqOut, RepIn]) => Future[RepOut]) {
  /**
    * @return
    *           (*   Service   *)
    * [ReqIn -> (Req2 -> Rep2) -> RepOut]
    *
    */
  def andThen[Req2, Rep2](next: Filter[ReqOut, RepIn, Req2, Rep2]) = new Filter[ReqIn, RepOut, Req2, Rep2] {
    override def apply(request: ReqIn, module: Module[Req2, Rep2]) = {
      val mdl: Module[ReqOut, RepIn] = new Module[ReqOut, RepIn] {
        override def apply(v1: ReqOut) = {
          try {
            println("andthen filter")
            next(v1, module)
          } catch {
            case NonFatal(e) => Future.failed(e)
          }
        }
      }
      Filter.this.apply(request, mdl)
    }
  }

  def andThen(module: Module[ReqOut, RepIn]): Module[ReqIn, RepOut] = {
    println("andthen module")
    new Module[ReqIn, RepOut] {
      def apply(request: ReqIn) = Filter.this.apply(request, module)
    }
  }
}

class TimeOutException extends RuntimeException

class TimeoutFilter[Req, Rep]( exception: TimeOutException,
                               timeout: Duration)
  extends Filter[Req, Rep, Req, Rep]
{

  def apply(request: Req, service: Module[Req, Rep]): Future[Rep] = {
    println("Timeout filter apply")
    val (cancelHodler,res) = FutureCancelable.cancellable(service(request))(println("canceled"))
    res.foreach(x =>
      println("TimeoutFilter not stop service completed")
    )
    //todo put Timeout Future to TimerTask which only use one thread.
    new FutureEx(res).withTimeout(timeout).recover{
      case exp @ (_: FutureTimeoutException | _: FutureTimeoutNotOccur) =>
        println("Timeout filter record timeout")
        cancelHodler()
        throw exception
      case _ =>
        println("other throw")
        throw new Exception("other")
    }
  }

  sealed class FutureTimeoutException extends RuntimeException
  sealed class FutureTimeoutNotOccur extends RuntimeException

  class FutureEx[T](f: Future[T]) {
    def withTimeout(ms: Long = 2000): Future[T] = Future.firstCompletedOf(List(f, {
      val p = Promise[T]
      Future {
        blocking(Thread.sleep(ms))
        if(!f.isCompleted) {
          println("FutureTimeoutException")
          p.tryFailure(new FutureTimeoutException)
        } else {
          println("FutureTimeoutNotOccur")
          p.tryFailure(new FutureTimeoutNotOccur)
        }
      }
      p.future
    }))

    def withTimeout(duration: Duration): Future[T] = withTimeout(duration.toMillis)
  }
}

case class AuthService(rst: String) {
  def auth(req: HttpReq): Future[String] = { //result can be other data type
    Future({
      println("service/module")
      Thread.sleep(5000)
      if(rst == "OK") "OK" else "Fail"
    })
  }
}

case class HttpReq()
case class HttpRsp()
case class AuthHttpReq(rst: String)

class RequireAuthentication(authService: AuthService)
  extends Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] {
  def apply(
             req: HttpReq,
             service: Module[AuthHttpReq, HttpRsp]
           ) = {
    println("authen service apply")
    authService.auth(req) flatMap {
      case "OK" =>
        println("authen service ok")
        service(AuthHttpReq("OK"))
      case ar =>
        println("authen service fail")
        Future.failed(
          new Exception())
    }
  }
}

/**
  * this example apply a timeout and authentication
  * - NOTE: service response is not the end , at thrift, it has a Builder to config detail action.Service and Filter is just a powerful
  * work stream to handle logic with scalable ways.
  * - consider filter a pre-deal actions to append message one filter by another, finally achieve service.
  */
object Test extends App {
  //filters
  lazy val timeoutFilter: Filter[HttpReq, HttpRsp, HttpReq, HttpRsp] = new TimeoutFilter[HttpReq, HttpRsp](new TimeOutException(), Duration(1, TimeUnit.SECONDS))
  lazy val authFilter: Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] = new RequireAuthentication(AuthService("OK"))

  //service
  lazy val serviceRequiringAuth: Module[AuthHttpReq, HttpRsp] = new Module[AuthHttpReq, HttpRsp] {
    override def apply(v1: AuthHttpReq): Future[HttpRsp] =
      v1 match {
      case AuthHttpReq("OK") =>
        println("authen pass")
        Future.successful(HttpRsp())
      case _ =>
        println("authen failed")
        Future.failed(new Exception("authen fail"))
    }
  }

  //create service
  val service = timeoutFilter andThen
                authFilter andThen
                serviceRequiringAuth

  service(HttpReq())

  Thread.currentThread().join()
}

object FutureCancelable {
  def cancellable[T](f: Future[T])(customCode: => Unit): (() => Unit, Future[T]) = {
    val p = Promise[T]
    val first = Future firstCompletedOf Seq(p.future, f)
    val cancellation: () => Unit = {
      () =>
        first.failed.foreach { case e => customCode}
        p failure new Exception
    }
    (cancellation, first)
  }
}