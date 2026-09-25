package com.mx_wj.protectedentity.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainRoutePlanner {
    private static final int SEARCH_RADIUS = 14;
    private static final int MAX_VISITED = 320;

    Step nextStep(ProtectedEntity mob, LivingEntity target) {
        Level level = mob.level();
        BlockPos start = mob.blockPosition();
        BlockPos goal = target.blockPosition();
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::score));
        Map<BlockPos, Double> bestCosts = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        RouteNode origin = new RouteNode(start, 0.0D, estimate(start, goal), null, null);
        RouteNode closest = origin;
        open.add(origin);
        bestCosts.put(start, 0.0D);

        while (!open.isEmpty() && visited.size() < MAX_VISITED) {
            RouteNode current = open.poll();
            if (!visited.add(current.position())) continue;
            if (estimate(current.position(), goal) < estimate(closest.position(), goal)) {
                closest = current;
            }
            if (isNearTarget(current.position(), goal)) {
                closest = current;
                break;
            }
            for (BlockPos candidate : neighbors(current.position(), goal)) {
                if (visited.contains(candidate) || Math.abs(candidate.getX() - start.getX()) > SEARCH_RADIUS
                        || Math.abs(candidate.getZ() - start.getZ()) > SEARCH_RADIUS
                        || Math.abs(candidate.getY() - start.getY()) > 5) continue;
                Step step = inspect(level, mob, current.position(), candidate, goal);
                if (step == null) continue;
                double cost = current.cost() + step.action().cost();
                if (cost >= bestCosts.getOrDefault(candidate, Double.MAX_VALUE)) continue;
                bestCosts.put(candidate, cost);
                open.add(new RouteNode(candidate, cost, cost + estimate(candidate, goal), current, step));
            }
        }

        if (closest == origin) return null;
        while (closest.previous() != origin) {
            closest = closest.previous();
        }
        return closest.step();
    }

    private List<BlockPos> neighbors(BlockPos current, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>(13);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = current.relative(direction);
            result.add(adjacent);
            result.add(adjacent.above());
            result.add(adjacent.below());
        }
        if (goal.getY() < current.getY()) result.add(current.below());
        return result;
    }

    private Step inspect(Level level, ProtectedEntity mob, BlockPos from, BlockPos destination, BlockPos goal) {
        if (destination.getY() < level.getMinBuildHeight()
                || destination.getY() + 1 >= level.getMaxBuildHeight()
                || !level.isLoaded(destination) || !level.isLoaded(destination.below())) return null;
        BlockState feet = level.getBlockState(destination);
        BlockState head = level.getBlockState(destination.above());
        BlockPos support = destination.below();
        BlockState floor = level.getBlockState(support);
        if (!floor.blocksMotion()) {
            if (destination.getY() < from.getY() || goal.getY() < mob.blockPosition().getY()
                    || feet.blocksMotion() || head.blocksMotion()
                    || !floor.canBeReplaced() || !level.getFluidState(support).isEmpty()
                    || !level.getBlockState(from.below()).blocksMotion()) return null;
            return new Step(destination, support, Action.PLACE);
        }
        if (feet.blocksMotion()) {
            return canBreak(level, destination, feet)
                    ? new Step(destination, destination, Action.BREAK) : null;
        }
        if (head.blocksMotion()) {
            return canBreak(level, destination.above(), head)
                    ? new Step(destination, destination.above(), Action.BREAK) : null;
        }
        if (level.getFluidState(destination).is(FluidTags.LAVA)) {
            return new Step(destination, destination, Action.DRAIN);
        }
        return new Step(destination, null, Action.WALK);
    }

    private boolean canBreak(Level level, BlockPos pos, BlockState state) {
        return state.getDestroySpeed(level, pos) >= 0.0F && !state.hasBlockEntity();
    }

    private static double estimate(BlockPos position, BlockPos goal) {
        int horizontal = Math.abs(position.getX() - goal.getX())
                + Math.abs(position.getZ() - goal.getZ());
        return Math.max(horizontal, Math.abs(position.getY() - goal.getY()));
    }

    private static boolean isNearTarget(BlockPos position, BlockPos goal) {
        return position.equals(goal);
    }

    record Step(BlockPos destination, BlockPos editPosition, Action action) {
    }

    enum Action {
        WALK(1.0D), DRAIN(2.0D), BREAK(3.0D), PLACE(4.0D);

        private final double cost;

        Action(double cost) {
            this.cost = cost;
        }

        double cost() {
            return this.cost;
        }
    }

    private record RouteNode(BlockPos position, double cost, double score,
                             RouteNode previous, Step step) {
    }
}
