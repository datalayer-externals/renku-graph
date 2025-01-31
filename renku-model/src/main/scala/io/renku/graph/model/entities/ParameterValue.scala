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

package io.renku.graph.model.entities

import cats.data.ValidatedNel
import cats.syntax.all._
import io.renku.cli.model.CliParameterValue
import io.renku.graph.model.Schemas._
import io.renku.graph.model.commandParameters
import io.renku.graph.model.entities.StepPlanCommandParameter._
import io.renku.graph.model.entityModel.{Location, LocationLike}
import io.renku.graph.model.parameterValues.{ResourceId, _}

sealed trait ParameterValue extends Product with Serializable {
  type ValueReference <: CommandParameterBase
  type Value

  val resourceId:     ResourceId
  val value:          Value
  val valueReference: ValueReference
}

object ParameterValue {

  sealed trait LocationParameterValue extends ParameterValue with Product with Serializable {
    override type ValueReference <: CommandInputOrOutput
    override type Value = LocationLike
  }

  final case class CommandParameterValue(resourceId: ResourceId, value: ValueOverride, valueReference: CommandParameter)
      extends ParameterValue {
    type ValueReference = CommandParameter
    type Value          = ValueOverride
  }

  final case class CommandInputValue(resourceId: ResourceId, value: LocationLike, valueReference: CommandInput)
      extends LocationParameterValue {
    type ValueReference = CommandInput
  }

  final case class CommandOutputValue(resourceId: ResourceId, value: LocationLike, valueReference: CommandOutput)
      extends LocationParameterValue {
    type ValueReference = CommandOutput
  }

  import io.renku.jsonld.JsonLDEncoder._
  import io.renku.jsonld.ontology._
  import io.renku.jsonld.syntax._
  import io.renku.jsonld.{EntityTypes, JsonLD, JsonLDEncoder}

  private val parameterValueTypes = EntityTypes of (schema / "PropertyValue", renku / "ParameterValue")

  implicit def encoder[PV <: ParameterValue]: JsonLDEncoder[PV] =
    JsonLDEncoder.instance {
      case CommandInputValue(resourceId, value, valueReference) =>
        JsonLD.entity(
          resourceId.asEntityId,
          parameterValueTypes,
          schema / "value"          -> value.asJsonLD,
          schema / "valueReference" -> valueReference.resourceId.asEntityId.asJsonLD
        )
      case CommandOutputValue(resourceId, value, valueReference) =>
        JsonLD.entity(
          resourceId.asEntityId,
          parameterValueTypes,
          schema / "value"          -> value.asJsonLD,
          schema / "valueReference" -> valueReference.resourceId.asEntityId.asJsonLD
        )
      case CommandParameterValue(resourceId, valueOverride, valueReference) =>
        JsonLD.entity(
          resourceId.asEntityId,
          parameterValueTypes,
          schema / "value"          -> valueOverride.asJsonLD,
          schema / "valueReference" -> valueReference.resourceId.asEntityId.asJsonLD
        )
    }

  def fromCli(cliParam: CliParameterValue, forPlan: StepPlan): ValidatedNel[String, ParameterValue] = {
    def maybeCommandParameter(resourceId: ResourceId, valueReferenceId: commandParameters.ResourceId) =
      forPlan
        .findParameter(valueReferenceId)
        .map(parameter => CommandParameterValue(resourceId, cliParam.value.asString, parameter))

    def maybeCommandInput(resourceId: ResourceId, valueReferenceId: commandParameters.ResourceId) =
      forPlan
        .findInput(valueReferenceId)
        .map(input => CommandInputValue(resourceId, Location.FileOrFolder(cliParam.value.asString), input))

    def maybeCommandOutput(resourceId: ResourceId, valueReferenceId: commandParameters.ResourceId) =
      forPlan
        .findOutput(valueReferenceId)
        .map(output => CommandOutputValue(resourceId, Location.FileOrFolder(cliParam.value.asString), output))

    List(maybeCommandParameter _, maybeCommandInput _, maybeCommandOutput _)
      .flatMap(_.apply(cliParam.id, cliParam.parameter)) match {
      case Nil =>
        s"ParameterValue points to a non-existing command parameter ${cliParam.parameter}".invalidNel
      case value :: Nil => value.validNel
      case _ =>
        s"ParameterValue points to multiple command parameters with ${cliParam.parameter}".invalidNel
    }
  }

  lazy val ontology: Type = Type.Def(
    Class(renku / "ParameterValue"),
    ObjectProperties(
      ObjectProperty(
        schema / "valueReference",
        StepPlanCommandParameter.CommandParameter.ontology,
        StepPlanCommandParameter.CommandInput.ontology,
        StepPlanCommandParameter.CommandOutput.ontology
      )
    ),
    DataProperties(DataProperty(schema / "value", xsd / "string"))
  )
}
