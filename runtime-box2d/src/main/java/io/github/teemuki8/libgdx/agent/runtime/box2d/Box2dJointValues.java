package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.joints.DistanceJoint;
import com.badlogic.gdx.physics.box2d.joints.PrismaticJoint;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJoint;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.Map;

final class Box2dJointValues {
    private Box2dJointValues() {}

    static void declare(EntityInspector inspector, Box2dInspection.JointEntry entry,
            Map<String, Box2dInspection.WorldEntry> worlds, AgentRuntime runtime) {
        inspector.property("id", () -> RuntimeValues.string(entry.id))
                .property("runtimeEntityId", () -> RuntimeValues.string(entry.entityId.value()))
                .property("worldId", () -> RuntimeValues.string(entry.parentId))
                .property("jointType", () -> RuntimeValues.enumValue(type(joint(entry))))
                .property("bodyAId", () -> RuntimeValues.string(entry.bodyA))
                .property("bodyBId", () -> RuntimeValues.string(entry.bodyB))
                .property("anchorA", () -> Box2dInspection.vector(joint(entry).getAnchorA()))
                .property("anchorB", () -> Box2dInspection.vector(joint(entry).getAnchorB()))
                .property("active", () -> joint(entry).isActive())
                .property("collideConnected", () -> joint(entry).getCollideConnected())
                .property("reactionForce", () -> reactionForce(entry, worlds))
                .property("reactionTorque", () -> reactionTorque(entry, worlds))
                .property("detail", () -> detail(joint(entry)));
    }

    private static RuntimeValue reactionForce(Box2dInspection.JointEntry entry,
            Map<String, Box2dInspection.WorldEntry> worlds) {
        Box2dWorldSpec spec = worlds.get(entry.parentId).spec;
        return spec.inverseStep().isPresent()
                ? Box2dInspection.vector(joint(entry).getReactionForce(
                        (float) spec.inverseStep().orElseThrow()))
                : RuntimeValues.nullValue();
    }

    private static RuntimeValue reactionTorque(Box2dInspection.JointEntry entry,
            Map<String, Box2dInspection.WorldEntry> worlds) {
        Box2dWorldSpec spec = worlds.get(entry.parentId).spec;
        return spec.inverseStep().isPresent()
                ? Box2dInspection.decimal(joint(entry).getReactionTorque(
                        (float) spec.inverseStep().orElseThrow()))
                : RuntimeValues.nullValue();
    }

    private static RuntimeValue detail(Joint joint) {
        if (joint instanceof DistanceJoint distance) {
            return RuntimeValues.object(
                    field("type", RuntimeValues.enumValue("DISTANCE")),
                    field("localAnchorA", Box2dInspection.vector(distance.getLocalAnchorA())),
                    field("localAnchorB", Box2dInspection.vector(distance.getLocalAnchorB())),
                    field("length", Box2dInspection.decimal(distance.getLength())),
                    field("frequency", Box2dInspection.decimal(distance.getFrequency())),
                    field("dampingRatio", Box2dInspection.decimal(distance.getDampingRatio())));
        }
        if (joint instanceof RevoluteJoint revolute) {
            return RuntimeValues.object(
                    field("type", RuntimeValues.enumValue("REVOLUTE")),
                    field("localAnchorA", Box2dInspection.vector(revolute.getLocalAnchorA())),
                    field("localAnchorB", Box2dInspection.vector(revolute.getLocalAnchorB())),
                    field("referenceAngle", Box2dInspection.decimal(revolute.getReferenceAngle())),
                    field("jointAngle", Box2dInspection.decimal(revolute.getJointAngle())),
                    field("jointSpeed", Box2dInspection.decimal(revolute.getJointSpeed())),
                    field("limitEnabled", RuntimeValues.bool(revolute.isLimitEnabled())),
                    field("lowerLimit", Box2dInspection.decimal(revolute.getLowerLimit())),
                    field("upperLimit", Box2dInspection.decimal(revolute.getUpperLimit())),
                    field("motorEnabled", RuntimeValues.bool(revolute.isMotorEnabled())),
                    field("motorSpeed", Box2dInspection.decimal(revolute.getMotorSpeed())),
                    field("maxMotorTorque",
                            Box2dInspection.decimal(revolute.getMaxMotorTorque())));
        }
        if (joint instanceof PrismaticJoint prismatic) {
            return RuntimeValues.object(
                    field("type", RuntimeValues.enumValue("PRISMATIC")),
                    field("localAnchorA", Box2dInspection.vector(prismatic.getLocalAnchorA())),
                    field("localAnchorB", Box2dInspection.vector(prismatic.getLocalAnchorB())),
                    field("localAxisA", Box2dInspection.vector(prismatic.getLocalAxisA())),
                    field("referenceAngle", Box2dInspection.decimal(prismatic.getReferenceAngle())),
                    field("translation", Box2dInspection.decimal(prismatic.getJointTranslation())),
                    field("jointSpeed", Box2dInspection.decimal(prismatic.getJointSpeed())),
                    field("limitEnabled", RuntimeValues.bool(prismatic.isLimitEnabled())),
                    field("lowerLimit", Box2dInspection.decimal(prismatic.getLowerLimit())),
                    field("upperLimit", Box2dInspection.decimal(prismatic.getUpperLimit())),
                    field("motorEnabled", RuntimeValues.bool(prismatic.isMotorEnabled())),
                    field("motorSpeed", Box2dInspection.decimal(prismatic.getMotorSpeed())),
                    field("maxMotorForce", Box2dInspection.decimal(prismatic.getMaxMotorForce())));
        }
        return RuntimeValues.object(
                field("type", RuntimeValues.enumValue("GENERIC")),
                field("nativeJointType", RuntimeValues.enumValue(type(joint))));
    }

    private static RuntimeValue.Field field(String name, RuntimeValue value) {
        return RuntimeValues.field(name, value);
    }

    private static Joint joint(Box2dInspection.JointEntry entry) {
        return Box2dInspection.live(entry);
    }

    private static String type(Joint joint) {
        return switch (joint.getType()) {
            case DistanceJoint -> "DISTANCE";
            case RevoluteJoint -> "REVOLUTE";
            case PrismaticJoint -> "PRISMATIC";
            default -> joint.getType().name().replace("Joint", "").toUpperCase();
        };
    }
}
