/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.eoricommoncomponent.frontend.services

import play.api.mvc.Results.Redirect

import javax.inject.{Inject, Singleton}
import play.api.mvc.{AnyContent, Request, Result}
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.{FeatureFlags, Sub02Controller}
import uk.gov.hmrc.eoricommoncomponent.frontend.domain._
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.messaging.ResponseCommon
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.messaging.ResponseCommon._
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.registration.UserLocation
import uk.gov.hmrc.eoricommoncomponent.frontend.domain.subscription.SubscriptionDetails
import uk.gov.hmrc.eoricommoncomponent.frontend.models.Service
import uk.gov.hmrc.eoricommoncomponent.frontend.services.cache.{RequestSessionData, SessionCache}
import uk.gov.hmrc.eoricommoncomponent.frontend.services.organisation.OrgTypeLookup
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.eoricommoncomponent.frontend.controllers.routes._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RegisterWithoutIdWithSubscriptionService @Inject() (
  registerWithoutIdService: RegisterWithoutIdService,
  sessionCache: SessionCache,
  requestSessionData: RequestSessionData,
  orgTypeLookup: OrgTypeLookup,
  sub02Controller: Sub02Controller,
  featureFlags: FeatureFlags
)(implicit ec: ExecutionContext) {

  def rowRegisterWithoutIdWithSubscription(loggedInUser: LoggedInUserWithEnrolments, service: Service)(implicit
    hc: HeaderCarrier,
    request: Request[AnyContent]
  ): Future[Result] = {

    def isRow = UserLocation.isRow(requestSessionData)

    def applicableForRegistration(rd: RegistrationDetails): Boolean = rd.safeId.id.isEmpty && isRow

    val (maybeOrgType, isNewCharityJourney) =
      (requestSessionData.userSelectedOrganisationType, featureFlags.useNewCharityEdgeCaseJourney)

    (maybeOrgType, isNewCharityJourney) match {
      case (Some(CdsOrganisationType.CharityPublicBodyNotForProfit), true) =>
        //This is temporary safeguard until ECC-1672 and ECC-1565 are completed. We don't know at this point what is the registration process for the new CharityPublicBodyNotForProfit journey
        Future.successful(Redirect(Sub02Controller.requestNotProcessed(service)))
      case _ =>
        sessionCache.registrationDetails flatMap {
          case rd if applicableForRegistration(rd) =>
            rowServiceCall(loggedInUser, service)
          case _ => createSubscription(service)(request)
        }
    }
  }

  def createSubscription(service: Service)(implicit request: Request[AnyContent]): Future[Result] =
    sub02Controller.subscribe(service)(request)

  private def rowServiceCall(loggedInUser: LoggedInUserWithEnrolments, service: Service)(implicit
    hc: HeaderCarrier,
    request: Request[AnyContent]
  ) = {

    def registerWithoutIdWithSubscription(
      orgType: Option[EtmpOrganisationType],
      regDetails: RegistrationDetails,
      subDetails: SubscriptionDetails
    ) =
      orgType match {
        case Some(NA) =>
          rowIndividualRegisterWithSubscription(
            loggedInUser,
            service,
            regDetails,
            subDetails,
            requestSessionData.userSelectedOrganisationType
          )
        case _ =>
          rowOrganisationRegisterWithSubscription(
            loggedInUser,
            service,
            regDetails,
            subDetails,
            requestSessionData.userSelectedOrganisationType
          )
      }

    for {
      orgType <- orgTypeLookup.etmpOrgTypeOpt
      rd      <- sessionCache.registrationDetails
      sd      <- sessionCache.subscriptionDetails
      call    <- registerWithoutIdWithSubscription(orgType, rd, sd)
    } yield call
  }

  private def rowIndividualRegisterWithSubscription(
    loggedInUser: LoggedInUserWithEnrolments,
    service: Service,
    registrationDetails: RegistrationDetails,
    subscriptionDetails: SubscriptionDetails,
    orgType: Option[CdsOrganisationType]
  )(implicit hc: HeaderCarrier, request: Request[AnyContent]) =
    subscriptionDetails.nameDobDetails.map(
      details =>
        registerWithoutIdService
          .registerIndividual(
            IndividualNameAndDateOfBirth(details.firstName, details.lastName, details.dateOfBirth),
            registrationDetails.address,
            subscriptionDetails.contactDetails,
            loggedInUser,
            orgType
          )
          .flatMap {
            case RegisterWithoutIDResponse(ResponseCommon(status, _, _, _), _) if status == StatusOK =>
              sub02Controller.subscribe(service)(request)
            case _ =>
              throw new RuntimeException("Registration of individual FAILED")
          }
    ) match {
      case Some(f) => f
      case None =>
        throw new IllegalArgumentException("Incorrect argument passed for cache Individual Registration")
    }

  private def rowOrganisationRegisterWithSubscription(
    loggedInUser: LoggedInUserWithEnrolments,
    service: Service,
    registrationDetails: RegistrationDetails,
    subscriptionDetails: SubscriptionDetails,
    orgType: Option[CdsOrganisationType]
  )(implicit hc: HeaderCarrier, request: Request[AnyContent]) =
    registerWithoutIdService
      .registerOrganisation(
        subscriptionDetails.name,
        registrationDetails.address,
        subscriptionDetails.contactDetails,
        loggedInUser,
        orgType
      )
      .flatMap {
        case RegisterWithoutIDResponse(ResponseCommon(status, _, _, _), _) if status == StatusOK =>
          sub02Controller.subscribe(service)(request)
        case _ =>
          throw new RuntimeException("Registration of organisation FAILED")
      }

}
