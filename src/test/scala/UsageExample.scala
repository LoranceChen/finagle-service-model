import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise, blocking}
import scala.util.{Failure, Success}

class TimeOutException extends RuntimeException

class TimeoutMap[Req, Rep]( exception: TimeOutException,
                            timeout: Duration)
  extends Filter[Req, Rep, Req, Rep]
{

  def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
    println("Timeout filter apply")
    val (cancelHodler,res) = FutureCancelable.cancellable(service(request))(println("canceled"))
    res.foreach(x =>
      println("TimeoutMap not stop service completed")
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
      println("service module")
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
             service: Service[AuthHttpReq, HttpRsp]
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

/**
  * this example apply a timeout and authentication
  * - NOTE: service response is not the end , at thrift where has a Builder to config detail action.Service and Map is just a powerful
  * work stream to handle logic with scalable ways.
  * - consider filter as a pre-deal actions to append message one filter by another, finally achieve service.
  */
object Test extends App {
  //filters
  lazy val timeoutFilter: Filter[HttpReq, HttpRsp, HttpReq, HttpRsp] = new TimeoutMap[HttpReq, HttpRsp](new TimeOutException(), Duration(3, TimeUnit.SECONDS))
  lazy val authFilter: Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] = new RequireAuthentication(AuthService("OK"))

  //service
  lazy val serviceRequiringAuth: Service[AuthHttpReq, HttpRsp] = new Service[AuthHttpReq, HttpRsp] {
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
  lazy val service = timeoutFilter andThen
    authFilter andThen
    serviceRequiringAuth

  val rst = service(HttpReq())

  rst.onComplete{
    case Success(value) =>
      println("rst success - " + value)

    case Failure(exception) =>
      println("rst fail - " + exception)

  }

  Thread.currentThread().join()
}


