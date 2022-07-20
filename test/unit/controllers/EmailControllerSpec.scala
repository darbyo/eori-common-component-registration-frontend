/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package unit.controllers

import common.pages.matching.AddressPageFactoring
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.Result
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.EmailController
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.auth.GroupEnrolmentExtractor
import uk.gov.hmrc.eoricommoncomponent.frontend.domain._
import uk.gov.hmrc.eoricommoncomponent.frontend.forms.models.email.EmailStatus
import uk.gov.hmrc.eoricommoncomponent.frontend.services.cache.SessionCache
import uk.gov.hmrc.eoricommoncomponent.frontend.services.email.EmailVerificationService
import uk.gov.hmrc.eoricommoncomponent.frontend.services.{
  Save4LaterService,
  SubscriptionProcessing,
  SubscriptionStatusService,
  UserGroupIdSubscriptionStatusCheckService
}
import uk.gov.hmrc.eoricommoncomponent.frontend.views.html.{
  enrolment_exists_group_standalone,
  enrolment_pending_against_group_id,
  enrolment_pending_for_user
}
import uk.gov.hmrc.http.HeaderCarrier
import util.ControllerSpec
import util.builders.AuthBuilder.withAuthorisedUser
import util.builders.{AuthActionMock, SessionBuilder}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EmailControllerSpec
    extends ControllerSpec with AddressPageFactoring with MockitoSugar with BeforeAndAfterEach with AuthActionMock {

  private val mockAuthConnector                  = mock[AuthConnector]
  private val mockAuthAction                     = authAction(mockAuthConnector)
  private val mockEmailVerificationService       = mock[EmailVerificationService]
  private val mockSave4LaterService              = mock[Save4LaterService]
  private val mockSessionCache                   = mock[SessionCache]
  private val mockSubscriptionStatusService      = mock[SubscriptionStatusService]
  private val groupEnrolmentExtractor            = mock[GroupEnrolmentExtractor]
  private val enrolmentPendingAgainstGroupIdView = instanceOf[enrolment_pending_against_group_id]
  private val enrolmentPendingForUserView        = instanceOf[enrolment_pending_for_user]
  private val enrolmentExistsGroupStandaloneView = instanceOf[enrolment_exists_group_standalone]

  private val infoXpath = "//*[@id='info']"

  private val userGroupIdSubscriptionStatusCheckService =
    new UserGroupIdSubscriptionStatusCheckService(mockSubscriptionStatusService, mockSave4LaterService)

  private val controller = new EmailController(
    mockAuthAction,
    mockEmailVerificationService,
    mockSessionCache,
    mcc,
    mockSave4LaterService,
    userGroupIdSubscriptionStatusCheckService,
    groupEnrolmentExtractor,
    enrolmentPendingForUserView,
    enrolmentPendingAgainstGroupIdView
  )

  private val emailStatus = EmailStatus(Some("test@example.com"))

  override def beforeEach: Unit = {
    when(mockSave4LaterService.fetchEmail(any[GroupId])(any[HeaderCarrier]))
      .thenReturn(Future.successful(Some(emailStatus)))
    when(
      mockEmailVerificationService
        .isEmailVerified(any[String])(any[HeaderCarrier])
    ).thenReturn(Future.successful(Some(true)))
    when(mockSave4LaterService.saveEmail(any(), any())(any[HeaderCarrier]))
      .thenReturn(Future.successful(()))
    when(mockSessionCache.saveEmail(any())(any[HeaderCarrier]))
      .thenReturn(Future.successful(true))
    when(mockSave4LaterService.fetchCacheIds(any())(any()))
      .thenReturn(Future.successful(None))
    when(groupEnrolmentExtractor.groupIdEnrolments(any())(any()))
      .thenReturn(Future.successful(List.empty))
    when(mockSave4LaterService.fetchProcessingService(any())(any(), any())).thenReturn(Future.successful(None))
  }

  val atarGroupEnrolment: EnrolmentResponse =
    EnrolmentResponse("HMRC-ATAR-ORG", "Active", List(KeyValue("EORINumber", "GB1234567890")))

  val cdsGroupEnrolment: EnrolmentResponse =
    EnrolmentResponse("HMRC-CUS-ORG", "Active", List(KeyValue("EORINumber", "GB1234567890")))

  "Viewing the form on Register" should {

    "display the form with no errors" in {
      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        val page = CdsPage(contentAsString(result))
        page.getElementsText(PageLevelErrorSummaryListXPath) shouldBe empty
      }
    }

    "redirect when cache has no email status" in {
      when(mockSave4LaterService.fetchEmail(any[GroupId])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/atar/register/matching/what-is-your-email")
      }
    }

    "redirect when email not verified" in {
      when(mockSave4LaterService.fetchEmail(any[GroupId])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(emailStatus.copy(isVerified = false))))
      when(
        mockEmailVerificationService
          .isEmailVerified(any[String])(any[HeaderCarrier])
      ).thenReturn(Future.successful(Some(false)))
      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/atar/register/matching/verify-your-email")
      }
    }

    "redirect when email verified" in {
      when(mockSave4LaterService.fetchEmail(any[GroupId])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(emailStatus.copy(isVerified = true))))
      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/atar/register/email-confirmed")
      }
    }

    "block when subscription is in progress" in {
      when(mockSave4LaterService.fetchCacheIds(any())(any()))
        .thenReturn(Future.successful(Some(CacheIds(InternalId("int-id"), SafeId("safe-id"), Some("atar")))))
      when(mockSubscriptionStatusService.getStatus(any(), any())(any()))
        .thenReturn(Future.successful(SubscriptionProcessing))

      showFormRegister() { result =>
        status(result) shouldBe OK
        val page = CdsPage(contentAsString(result))
        page.title should startWith("There is a problem")
        page.getElementText(infoXpath) should include(
          "The Government Gateway ID you used to sign in is part of a team that has already applied for an EORI number"
        )
      }
    }

    "redirect when group enrolled to service" in {
      when(groupEnrolmentExtractor.groupIdEnrolments(any())(any()))
        .thenReturn(Future.successful(List(atarGroupEnrolment)))

      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/atar/register/enrolment-already-exists-for-group")
      }
    }

    "redirect when user has existing EORI" in {
      when(groupEnrolmentExtractor.groupIdEnrolments(any())(any()))
        .thenReturn(Future.successful(List(cdsGroupEnrolment)))

      showFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/atar/register/you-already-have-an-eori")
      }
    }

    "redirect and display when group enrolled to service and Eori is retreived in standalone journey " in {
      when(groupEnrolmentExtractor.groupIdEnrolments(any())(any()))
        .thenReturn(Future.successful(List(cdsGroupEnrolment)))
      when(mockSessionCache.saveEori(any[Eori])(any[HeaderCarrier]))
        .thenReturn(Future.successful(true))
      showStandaloneFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/eori-only/register/cds-enrolment-exists-for-group")
      }
    }

    "redirect and display when group enrolled to service even if Eori couldn't be retrieved in standalone journey " in {
      when(groupEnrolmentExtractor.groupIdEnrolments(any())(any()))
        .thenReturn(Future.successful(List(cdsGroupEnrolment.copy(identifiers = List()))))
      showStandaloneFormRegister() { result =>
        status(result) shouldBe SEE_OTHER
        await(result).header.headers("Location") should endWith("/eori-only/register/cds-enrolment-exists-for-group")
      }
    }

  }

  private def showFormRegister(userId: String = defaultUserId)(test: Future[Result] => Any): Unit =
    showForm(userId)(test)

  private def showForm(userId: String)(test: Future[Result] => Any) {
    withAuthorisedUser(userId, mockAuthConnector)
    test(controller.form(atarService).apply(SessionBuilder.buildRequestWithSessionAndPath("/atar", userId)))
  }

  private def showStandaloneFormRegister(userId: String = defaultUserId)(test: Future[Result] => Any): Unit =
    showStandaloneForm(userId)(test)

  private def showStandaloneForm(userId: String = defaultUserId)(test: Future[Result] => Any): Unit = {
    withAuthorisedUser(userId, mockAuthConnector)
    test(controller.form(eoriOnlyService).apply(SessionBuilder.buildRequestWithSessionAndPath("/eori-only", userId)))
  }

}
