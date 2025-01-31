/*
 * Copyright 2023 Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
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

package io.renku.graph.model
package entities

import Schemas.{prov, renku, schema}
import StepPlanCommandParameter.{CommandInput, CommandOutput, CommandParameter}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all._
import io.renku.cli.model.{CliCompositePlan, CliPlan, CliStepPlan}
import io.renku.graph.model.entities.Plan.Derivation
import io.renku.graph.model.plans._
import io.renku.jsonld.{EntityTypes, JsonLD, JsonLDEncoder}
import io.renku.jsonld.ontology._
import io.renku.jsonld.syntax._

import scala.math.Ordering.Implicits._

sealed trait Plan extends Product with Serializable {
  val resourceId:       ResourceId
  val name:             Name
  val maybeDescription: Option[Description]
  val creators:         List[Person]
  val dateCreated:      DateCreated
  val keywords:         List[Keyword]

  type PlanGroup <: Plan

  final def fold[P](spnm: StepPlan.NonModified => P,
                    spm:  StepPlan.Modified => P,
                    cpnm: CompositePlan.NonModified => P,
                    cpm:  CompositePlan.Modified => P
  ): P = this match {
    case sp: StepPlan      => sp.fold(spnm, spm)
    case cp: CompositePlan => cp.fold(cpnm, cpm)
  }
}

object Plan {

  final case class Derivation(derivedFrom: DerivedFrom, originalResourceId: ResourceId)

  def fromCli(cliPlan: CliPlan)(implicit renkuUrl: RenkuUrl): ValidatedNel[String, Plan] =
    cliPlan.fold(StepPlan.fromCli, CompositePlan.fromCli)

  implicit def entityFunctions(implicit gitLabApiUrl: GitLabApiUrl): EntityFunctions[Plan] =
    new EntityFunctions[Plan] {
      val findAllPersons: Plan => Set[Person]               = _.creators.toSet
      val encoder:        GraphClass => JsonLDEncoder[Plan] = Plan.encoder(gitLabApiUrl, _)
    }

  implicit def encoder(implicit gitLabApiUrl: GitLabApiUrl, graphClass: GraphClass): JsonLDEncoder[Plan] =
    JsonLDEncoder.instance {
      case p: StepPlan      => p.asJsonLD
      case p: CompositePlan => p.asJsonLD
    }

  lazy val ontology: Type =
    Type.Def(
      // This prov/Plan can be a renku/Plan or renku/CompositePlan
      Class(prov / "Plan"),
      ObjectProperties(
        ObjectProperty(schema / "creator", Person.Ontology.typeDef)
      ),
      DataProperties(
        DataProperty(schema / "name", xsd / "string"),
        DataProperty(schema / "description", xsd / "string"),
        DataProperty(schema / "dateCreated", xsd / "dateTime"),
        DataProperty(schema / "keywords", xsd / "string")
      )
    )
}

sealed trait StepPlan extends Plan with StepPlanAlg {
  val maybeCommand:             Option[Command]
  val maybeProgrammingLanguage: Option[ProgrammingLanguage]
  val parameters:               List[CommandParameter]
  val inputs:                   List[CommandInput]
  val outputs:                  List[CommandOutput]
  val successCodes:             List[SuccessCode]
  override type PlanGroup = StepPlan

  def fold[A](nm: StepPlan.NonModified => A, mm: StepPlan.Modified => A): A
}

sealed trait StepPlanAlg {
  self: StepPlan =>

  def findParameter(parameterId: commandParameters.ResourceId): Option[CommandParameter] =
    parameters.find(_.resourceId == parameterId)

  def findInput(parameterId: commandParameters.ResourceId): Option[CommandInput] =
    inputs.find(_.resourceId == parameterId)

  def findOutput(parameterId: commandParameters.ResourceId): Option[CommandOutput] =
    outputs.find(_.resourceId == parameterId)
}

object StepPlan {
  import Plan._

  final case class NonModified(resourceId:               ResourceId,
                               name:                     Name,
                               maybeDescription:         Option[Description],
                               creators:                 List[Person],
                               dateCreated:              DateCreated,
                               keywords:                 List[Keyword],
                               maybeCommand:             Option[Command],
                               maybeProgrammingLanguage: Option[ProgrammingLanguage],
                               parameters:               List[CommandParameter],
                               inputs:                   List[CommandInput],
                               outputs:                  List[CommandOutput],
                               successCodes:             List[SuccessCode]
  ) extends StepPlan {
    override def fold[P](spnm: StepPlan.NonModified => P, spm: StepPlan.Modified => P): P = spnm(this)
  }

  final case class Modified(resourceId:               ResourceId,
                            name:                     Name,
                            maybeDescription:         Option[Description],
                            creators:                 List[Person],
                            dateCreated:              DateCreated,
                            keywords:                 List[Keyword],
                            maybeCommand:             Option[Command],
                            maybeProgrammingLanguage: Option[ProgrammingLanguage],
                            parameters:               List[CommandParameter],
                            inputs:                   List[CommandInput],
                            outputs:                  List[CommandOutput],
                            successCodes:             List[SuccessCode],
                            derivation:               Derivation,
                            maybeInvalidationTime:    Option[InvalidationTime]
  ) extends StepPlan {
    override def fold[P](spnm: StepPlan.NonModified => P, spm: StepPlan.Modified => P): P = spm(this)
  }

  def from(resourceId:               ResourceId,
           name:                     Name,
           maybeDescription:         Option[Description],
           maybeCommand:             Option[Command],
           creators:                 List[Person],
           dateCreated:              DateCreated,
           maybeProgrammingLanguage: Option[ProgrammingLanguage],
           keywords:                 List[Keyword],
           parameters:               List[CommandParameter],
           inputs:                   List[CommandInput],
           outputs:                  List[CommandOutput],
           successCodes:             List[SuccessCode]
  ): ValidatedNel[String, StepPlan.NonModified] = NonModified(resourceId,
                                                              name,
                                                              maybeDescription,
                                                              creators,
                                                              dateCreated,
                                                              keywords,
                                                              maybeCommand,
                                                              maybeProgrammingLanguage,
                                                              parameters,
                                                              inputs,
                                                              outputs,
                                                              successCodes
  ).validNel

  def from(resourceId:               ResourceId,
           name:                     Name,
           maybeDescription:         Option[Description],
           maybeCommand:             Option[Command],
           creators:                 List[Person],
           dateCreated:              DateCreated,
           maybeProgrammingLanguage: Option[ProgrammingLanguage],
           keywords:                 List[Keyword],
           parameters:               List[CommandParameter],
           inputs:                   List[CommandInput],
           outputs:                  List[CommandOutput],
           successCodes:             List[SuccessCode],
           derivation:               Derivation,
           maybeInvalidationTime:    Option[InvalidationTime]
  ): ValidatedNel[String, StepPlan] = {
    lazy val validateInvalidationTime: ValidatedNel[String, Unit] = maybeInvalidationTime match {
      case None => Validated.validNel(())
      case Some(time) =>
        Validated.condNel(
          test = (time.value compareTo dateCreated.value) >= 0,
          (),
          show"Invalidation time $time on StepPlan $resourceId is older than dateCreated $dateCreated"
        )
    }

    validateInvalidationTime.map(_ =>
      Modified(
        resourceId,
        name,
        maybeDescription,
        creators,
        dateCreated,
        keywords,
        maybeCommand,
        maybeProgrammingLanguage,
        parameters,
        inputs,
        outputs,
        successCodes,
        derivation,
        maybeInvalidationTime
      )
    )
  }

  def fromCli(cliPlan: CliStepPlan)(implicit renkuUrl: RenkuUrl): ValidatedNel[String, StepPlan] = {
    val creatorsV = cliPlan.creators.traverse(Person.fromCli)
    val inputsV   = cliPlan.inputs.traverse(StepPlanCommandParameter.CommandInput.fromCli)
    val outputsV  = cliPlan.outputs.traverse(StepPlanCommandParameter.CommandOutput.fromCli)
    val paramsV   = cliPlan.parameters.traverse(StepPlanCommandParameter.CommandParameter.fromCli)
    val all       = (creatorsV, inputsV, outputsV, paramsV).mapN(Tuple4.apply)
    all.andThen { case (creators, inputs, outputs, params) =>
      (cliPlan.derivedFrom, cliPlan.invalidationTime) match {
        case (Some(derivedFrom), _) =>
          from(
            cliPlan.id,
            cliPlan.name,
            cliPlan.description,
            cliPlan.command,
            creators,
            cliPlan.dateCreated,
            None,
            cliPlan.keywords,
            params,
            inputs,
            outputs,
            cliPlan.successCodes,
            Derivation(derivedFrom, ResourceId(derivedFrom.value)),
            cliPlan.invalidationTime
          )
        case (None, None) =>
          from(
            cliPlan.id,
            cliPlan.name,
            cliPlan.description,
            cliPlan.command,
            creators,
            cliPlan.dateCreated,
            None,
            cliPlan.keywords,
            params,
            inputs,
            outputs,
            cliPlan.successCodes
          )
        case (None, Some(_)) =>
          show"Plan ${cliPlan.id} has no parent but invalidation time".invalidNel
      }
    }
  }

  val entityTypes: EntityTypes =
    EntityTypes of (renku / "Plan", prov / "Plan", schema / "Action", schema / "CreativeWork")

  implicit def encoder[P <: StepPlan](implicit gitLabApiUrl: GitLabApiUrl, graphClass: GraphClass): JsonLDEncoder[P] =
    JsonLDEncoder.instance {
      case plan: StepPlan.NonModified =>
        JsonLD.entity(
          plan.resourceId.asEntityId,
          entityTypes,
          schema / "name"                -> plan.name.asJsonLD,
          schema / "description"         -> plan.maybeDescription.asJsonLD,
          renku / "command"              -> plan.maybeCommand.asJsonLD,
          schema / "creator"             -> plan.creators.asJsonLD,
          schema / "dateCreated"         -> plan.dateCreated.asJsonLD,
          schema / "programmingLanguage" -> plan.maybeProgrammingLanguage.asJsonLD,
          schema / "keywords"            -> plan.keywords.asJsonLD,
          renku / "hasArguments"         -> plan.parameters.asJsonLD,
          renku / "hasInputs"            -> plan.inputs.asJsonLD,
          renku / "hasOutputs"           -> plan.outputs.asJsonLD,
          renku / "topmostDerivedFrom"   -> plan.resourceId.asEntityId.asJsonLD,
          renku / "successCodes"         -> plan.successCodes.asJsonLD
        )
      case plan: StepPlan.Modified =>
        JsonLD.entity(
          plan.resourceId.asEntityId,
          entityTypes,
          schema / "name"                -> plan.name.asJsonLD,
          schema / "description"         -> plan.maybeDescription.asJsonLD,
          renku / "command"              -> plan.maybeCommand.asJsonLD,
          schema / "creator"             -> plan.creators.asJsonLD,
          schema / "dateCreated"         -> plan.dateCreated.asJsonLD,
          schema / "programmingLanguage" -> plan.maybeProgrammingLanguage.asJsonLD,
          schema / "keywords"            -> plan.keywords.asJsonLD,
          renku / "hasArguments"         -> plan.parameters.asJsonLD,
          renku / "hasInputs"            -> plan.inputs.asJsonLD,
          renku / "hasOutputs"           -> plan.outputs.asJsonLD,
          renku / "successCodes"         -> plan.successCodes.asJsonLD,
          prov / "wasDerivedFrom"        -> plan.derivation.derivedFrom.asJsonLD,
          renku / "topmostDerivedFrom"   -> plan.derivation.originalResourceId.asEntityId.asJsonLD,
          prov / "invalidatedAtTime"     -> plan.maybeInvalidationTime.asJsonLD
        )
    }

  lazy val ontology: Type = {
    lazy val planClass = Class(renku / "Plan", ParentClass(Plan.ontology))
    Type.Def(
      planClass,
      ObjectProperties(
        ObjectProperty(renku / "hasArguments", StepPlanCommandParameter.CommandParameter.ontology),
        ObjectProperty(renku / "hasInputs", StepPlanCommandParameter.CommandInput.ontology),
        ObjectProperty(renku / "hasOutputs", StepPlanCommandParameter.CommandOutput.ontology),
        ObjectProperty(schema / "creator", Person.Ontology.typeDef),
        ObjectProperty(prov / "wasDerivedFrom", planClass),
        ObjectProperty(renku / "topmostDerivedFrom", planClass)
      ),
      DataProperties(
        DataProperty(schema / "name", xsd / "string"),
        DataProperty(schema / "description", xsd / "string"),
        DataProperty(renku / "command", xsd / "string"),
        DataProperty(schema / "dateCreated", xsd / "dateTime"),
        DataProperty(schema / "programmingLanguage", xsd / "string"),
        DataProperty(schema / "keywords", xsd / "string"),
        DataProperty(renku / "successCodes", xsd / "int"),
        DataProperty(prov / "invalidatedAtTime", xsd / "dateTime")
      )
    )
  }
}

sealed trait CompositePlan extends Plan {
  def plans:    NonEmptyList[ResourceId]
  def mappings: List[ParameterMapping]
  def links:    List[ParameterLink]

  def fold[A](nm: CompositePlan.NonModified => A, mm: CompositePlan.Modified => A): A
}

object CompositePlan {

  final case class NonModified(
      resourceId:       ResourceId,
      name:             Name,
      maybeDescription: Option[Description],
      creators:         List[Person],
      dateCreated:      DateCreated,
      keywords:         List[Keyword],
      plans:            NonEmptyList[ResourceId],
      mappings:         List[ParameterMapping],
      links:            List[ParameterLink]
  ) extends CompositePlan {
    override def fold[P](cpnm: CompositePlan.NonModified => P, cpm: CompositePlan.Modified => P): P = cpnm(this)
  }

  final case class Modified(
      resourceId:            ResourceId,
      name:                  Name,
      maybeDescription:      Option[Description],
      creators:              List[Person],
      dateCreated:           DateCreated,
      keywords:              List[Keyword],
      plans:                 NonEmptyList[ResourceId],
      mappings:              List[ParameterMapping],
      links:                 List[ParameterLink],
      maybeInvalidationTime: Option[InvalidationTime],
      derivation:            Plan.Derivation
  ) extends CompositePlan {
    override def fold[P](cpnm: CompositePlan.NonModified => P, cpm: CompositePlan.Modified => P): P = cpm(this)
  }

  def fromCli(cliPlan: CliCompositePlan)(implicit renkuUrl: RenkuUrl): ValidatedNel[String, CompositePlan] = {
    val creatorsV   = cliPlan.creators.traverse(Person.fromCli)
    val childPlansV = cliPlan.plans.traverse(Plan.fromCli)
    val linksV      = cliPlan.links.traverse(ParameterLink.fromCli)
    val mappingsV   = cliPlan.mappings.traverse(ParameterMapping.fromCli)
    val all         = (creatorsV, childPlansV, linksV, mappingsV).mapN(Tuple4.apply)
    all.andThen { case (creators, childPlans, links, mappings) =>
      (cliPlan.derivedFrom, cliPlan.invalidationTime) match {
        case (None, None) =>
          validate(
            CompositePlan.NonModified(
              cliPlan.id,
              cliPlan.name,
              cliPlan.description,
              creators,
              cliPlan.dateCreated,
              cliPlan.keywords,
              childPlans.map(_.resourceId),
              mappings,
              links
            )
          )
        case (Some(derivedFrom), mit) =>
          validate(
            CompositePlan.Modified(
              cliPlan.id,
              cliPlan.name,
              cliPlan.description,
              creators,
              cliPlan.dateCreated,
              cliPlan.keywords,
              childPlans.map(_.resourceId),
              mappings,
              links,
              mit,
              Derivation(derivedFrom, ResourceId(derivedFrom.value))
            )
          )
        case (None, Some(_)) =>
          show"Plan ${cliPlan.id} has no parent but invalidation time".invalidNel
      }
    }
  }

  // noinspection TypeAnnotation
  object Ontology {
    val entityTypes: EntityTypes =
      EntityTypes.of(renku / "CompositePlan", prov / "Plan", schema / "Action", schema / "CreativeWork")

    val compositePlanType = Class(renku / "CompositePlan", ParentClass(Plan.ontology))

    val name               = schema / "name"
    val description        = schema / "description"
    val dateCreated        = schema / "dateCreated"
    val keywords           = schema / "keywords"
    val invalidatedAtTime  = prov / "invalidatedAtTime"
    val creators           = schema / "creator"
    val wasDerivedFrom     = prov / "wasDerivedFrom"
    val topmostDerivedFrom = renku / "topmostDerivedFrom"
    val hasSubprocess      = renku / "hasSubprocess"
    val workflowLinks      = renku / "workflowLinks"
    val hasMappings        = renku / "hasMappings"

    val typeDef: Type =
      Type.Def(
        compositePlanType,
        ObjectProperties(
          ObjectProperty(creators, Person.Ontology.typeDef),
          ObjectProperty(wasDerivedFrom, compositePlanType),
          ObjectProperty(topmostDerivedFrom, compositePlanType),
          ObjectProperty(hasSubprocess, Plan.ontology),
          ObjectProperty(workflowLinks, ParameterLink.Ontology.typeDef),
          ObjectProperty(hasMappings, ParameterMapping.Ontology.typeDef)
        ),
        DataProperties(
          DataProperty(name, xsd / "string"),
          DataProperty(description, xsd / "string"),
          DataProperty(dateCreated, xsd / "dateTime"),
          DataProperty(keywords, xsd / "string"),
          DataProperty(invalidatedAtTime, xsd / "dateTime")
        )
      )
  }

  implicit def encoder(implicit gitLabApiUrl: GitLabApiUrl, graphClass: GraphClass): JsonLDEncoder[CompositePlan] =
    JsonLDEncoder.instance { plan =>
      JsonLD.entity(
        plan.resourceId.asEntityId,
        Ontology.entityTypes,
        Map(
          Ontology.name          -> plan.name.asJsonLD,
          Ontology.description   -> plan.maybeDescription.asJsonLD,
          Ontology.creators      -> plan.creators.asJsonLD,
          Ontology.dateCreated   -> plan.dateCreated.asJsonLD,
          Ontology.keywords      -> plan.keywords.asJsonLD,
          Ontology.hasSubprocess -> plan.plans.toList.asJsonLD,
          Ontology.workflowLinks -> plan.links.asJsonLD,
          Ontology.hasMappings   -> plan.mappings.asJsonLD
        ) ++ PlanLens.getModifiedProperties
          .get(plan)
          .map { case (derivation, invalidationTime) =>
            Map(
              Ontology.wasDerivedFrom     -> derivation.derivedFrom.asJsonLD,
              Ontology.topmostDerivedFrom -> derivation.originalResourceId.asEntityId.asJsonLD,
              Ontology.invalidatedAtTime  -> invalidationTime.asJsonLD
            )
          }
          .getOrElse(Map(Ontology.topmostDerivedFrom -> plan.resourceId.asEntityId.asJsonLD))
      )
    }

  def validate(plan: CompositePlan): ValidatedNel[String, CompositePlan] = {
    val selfMapping =
      plan.mappings.traverse_(pm =>
        Validated.condNel(pm.mappedParameter.forall(_ != pm.resourceId),
                          (),
                          show"Parameter ${pm.resourceId} maps to itself."
        )
      )

    val checks =
      plan match {
        case _: NonModified => Validated.validNel(())
        case p: Modified =>
          val checkInvalidationTime =
            p.maybeInvalidationTime
              .map(time =>
                Validated.condNel(
                  time.value >= p.dateCreated.value,
                  (),
                  show"Invalidation time $time on CompositePlan ${p.resourceId} is older than dateCreated ${p.dateCreated}"
                )
              )
              .getOrElse(Validated.validNel(()))

          checkInvalidationTime
      }

    (selfMapping |+| checks).as(plan)
  }
}
