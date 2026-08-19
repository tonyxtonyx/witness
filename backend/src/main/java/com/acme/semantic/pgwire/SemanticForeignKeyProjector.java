package com.acme.semantic.pgwire;

import com.acme.semantic.model.SemanticModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Projects governed semantic relationships into read-only relational metadata. */
final class SemanticForeignKeyProjector {

  List<VirtualForeignKey> project(SemanticModel model) {
    Objects.requireNonNull(model, "model");
    List<VirtualForeignKey> result = new ArrayList<>();

    for (SemanticModel.SemanticObject source : model.objects().values()) {
      for (SemanticModel.Relationship relationship : source.spec().relationships()) {
        SemanticModel.SemanticObject target =
            model.resolveObject(relationship.targetObject(), model.domain(source)).value();
        if (target == null
            || relationship.cardinality() == null
            || relationship.sourceFields().isEmpty()
            || relationship.sourceFields().size() != relationship.targetFields().size()) {
          continue;
        }

        switch (relationship.cardinality()) {
          case many_to_one, one_to_one ->
              result.add(
                  foreignKey(
                      model,
                      relationship,
                      source,
                      relationship.sourceFields(),
                      target,
                      relationship.targetFields(),
                      source));
          case one_to_many ->
              result.add(
                  foreignKey(
                      model,
                      relationship,
                      target,
                      relationship.targetFields(),
                      source,
                      relationship.sourceFields(),
                      source));
          case many_to_many -> {
            // A many-to-many semantic edge is not one relational foreign key. Model the bridge
            // object explicitly when pgwire FK metadata is required.
          }
        }
      }
    }

    return List.copyOf(result);
  }

  private VirtualForeignKey foreignKey(
      SemanticModel model,
      SemanticModel.Relationship relationship,
      SemanticModel.SemanticObject foreignKeyObject,
      List<String> foreignKeyFields,
      SemanticModel.SemanticObject primaryKeyObject,
      List<String> primaryKeyFields,
      SemanticModel.SemanticObject declaredBy) {
    return new VirtualForeignKey(
        relationship.name(),
        model.domain(primaryKeyObject),
        primaryKeyObject.metadata().name(),
        primaryKeyFields,
        model.domain(foreignKeyObject),
        foreignKeyObject.metadata().name(),
        foreignKeyFields,
        relationship.cardinality(),
        model.domain(declaredBy) + "." + declaredBy.metadata().name());
  }

  record VirtualForeignKey(
      String name,
      String primaryKeyDomain,
      String primaryKeyObject,
      List<String> primaryKeyFields,
      String foreignKeyDomain,
      String foreignKeyObject,
      List<String> foreignKeyFields,
      SemanticModel.Cardinality semanticCardinality,
      String declaredBy) {
    VirtualForeignKey {
      primaryKeyFields = List.copyOf(primaryKeyFields);
      foreignKeyFields = List.copyOf(foreignKeyFields);
    }

    String primaryKeyName() {
      return primaryKeyObject + "_pkey";
    }

    boolean enforced() {
      return false;
    }
  }
}
