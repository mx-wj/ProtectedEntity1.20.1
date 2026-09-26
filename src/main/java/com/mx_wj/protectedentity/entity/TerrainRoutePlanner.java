package com.mx_wj.protectedentity.entity;

import java.util.ArrayDeque;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class TerrainRoutePlanner {
    private static final int SEARCH_RADIUS = 14;
    private static final int SEARCH_HEIGHT = 24;
    private static final int MAX_DESCENT_HEIGHT = 64;
    private static final int MAX_VISITED = 1024;
    private static final double PILLAR_HORIZONTAL_RANGE = 2.0D;
    private final ArrayDeque<BlockPos> detour = new ArrayDeque<>();
    private BlockPos detourGoal;
    private List<BlockPos> levelRoute = List.of();
    private BlockPos levelRouteGoal;

    Step nextStep(ProtectedEntity mob, LivingEntity target, boolean canModifyTerrain, boolean aggressive) {
        Level level = mob.level();
        BlockPos start = mob.blockPosition();
        BlockPos goal = targetGroundPosition(target);
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator
                .comparingDouble(RouteNode::remainingDistance).thenComparingDouble(RouteNode::cost));
        Map<BlockPos, Double> bestCosts = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        RouteNode origin = new RouteNode(start, 0.0D, estimate(start, goal), null, null, Map.of());
        double horizontalX = target.getX() - mob.getX();
        double horizontalZ = target.getZ() - mob.getZ();
        if (goal.getY() > start.getY()
                && horizontalX * horizontalX + horizontalZ * horizontalZ <= PILLAR_HORIZONTAL_RANGE * PILLAR_HORIZONTAL_RANGE) {
            Step pillar = inspect(level, origin, start.above(), goal);
            if (pillar != null && (canModifyTerrain || pillar.edits().isEmpty())) {
                this.clearRoute();
                return pillar;
            }
        }
        Step descendingStep = this.descent(level, origin, goal, canModifyTerrain);
        if (descendingStep != null) {
            this.clearRoute();
            return descendingStep;
        }
        Step detourStep = continueDetour(level, origin, goal, canModifyTerrain);
        if (detourStep != null) return detourStep;
        Step levelStep = levelLineStep(level, origin, goal, canModifyTerrain);
        if (levelStep != null) return levelStep;
        Step risingStep = forwardAscent(level, origin, goal, canModifyTerrain);
        if (risingStep != null) return risingStep;
        Step directStep = greedyStep(level, origin, goal, canModifyTerrain);
        if (directStep != null) return directStep;
        RouteNode closest = origin;
        open.add(origin);
        bestCosts.put(start, 0.0D);

        while (!open.isEmpty() && visited.size() < MAX_VISITED) {
            RouteNode current = open.poll();
            if (current.cost() > bestCosts.getOrDefault(current.position(), Double.MAX_VALUE)
                    || !visited.add(current.position())) continue;
            if (current.remainingDistance() < closest.remainingDistance()
                    || current.remainingDistance() == closest.remainingDistance()
                    && current.cost() < closest.cost()) {
                closest = current;
            }
            if (current.position().equals(goal)) {
                closest = current;
                break;
            }
            for (BlockPos candidate : neighbors(current.position(), goal)) {
                if (visited.contains(candidate) || Math.abs(candidate.getX() - start.getX()) > SEARCH_RADIUS
                        || Math.abs(candidate.getZ() - start.getZ()) > SEARCH_RADIUS
                        || Math.abs(candidate.getY() - start.getY()) > SEARCH_HEIGHT) continue;
                Step step = inspect(level, current, candidate, goal);
                if (step == null || !canModifyTerrain && !step.edits().isEmpty()) continue;
                double cost = current.cost() + estimate(current.position(), candidate);
                for (Edit edit : step.edits()) {
                    cost += aggressive && edit.action() == Action.PLACE
                            ? edit.action().cost * 0.25D : edit.action().cost;
                }
                if (cost >= bestCosts.getOrDefault(candidate, Double.MAX_VALUE)) continue;
                Map<BlockPos, BlockState> plannedBlocks = current.plannedBlocks();
                if (!step.edits().isEmpty()) {
                    plannedBlocks = new HashMap<>(plannedBlocks);
                    for (Edit edit : step.edits()) {
                        plannedBlocks.put(edit.position(), edit.result());
                    }
                }
                bestCosts.put(candidate, cost);
                open.add(new RouteNode(candidate, cost, estimate(candidate, goal), current, step, plannedBlocks));
            }
        }

        if (closest == origin) return null;
        this.detourGoal = goal;
        for (RouteNode node = closest; node.previous() != null; node = node.previous()) {
            this.detour.addFirst(node.position());
        }
        while (closest.previous() != origin) closest = closest.previous();
        return closest.step();
    }

    void clearRoute() {
        this.clearDetour();
        this.levelRoute = List.of();
        this.levelRouteGoal = null;
    }

    private void clearDetour() {
        this.detour.clear();
        this.detourGoal = null;
    }

    private Step continueDetour(Level level, RouteNode origin, BlockPos goal, boolean canModifyTerrain) {
        if (this.detourGoal != null && estimate(this.detourGoal, goal) > 2.0D) this.clearDetour();
        while (!this.detour.isEmpty() && this.detour.peekFirst().equals(origin.position())) this.detour.removeFirst();
        if (!this.detour.isEmpty()) {
            BlockPos next = this.detour.peekFirst();
            BlockPos from = origin.position();
            if (Math.abs(next.getX() - from.getX()) <= 1 && Math.abs(next.getY() - from.getY()) <= 1
                    && Math.abs(next.getZ() - from.getZ()) <= 1) {
                Step step = inspect(level, origin, next, goal);
                if (step != null && (canModifyTerrain || step.edits().isEmpty())) return step;
            }
        }
        this.clearDetour();
        return null;
    }

    private Step levelLineStep(Level level, RouteNode origin, BlockPos goal, boolean canModifyTerrain) {
        BlockPos start = origin.position();
        if (start.getY() != goal.getY()) {
            this.levelRoute = List.of();
            this.levelRouteGoal = null;
            return null;
        }
        int currentIndex = this.levelRoute.indexOf(start);
        if (!goal.equals(this.levelRouteGoal) || currentIndex < 0) {
            this.levelRoute = levelLine(start, goal);
            this.levelRouteGoal = goal;
            currentIndex = 0;
        }
        if (currentIndex + 1 >= this.levelRoute.size()) return null;
        Step step = inspect(level, origin, this.levelRoute.get(currentIndex + 1), goal);
        if (step != null && (canModifyTerrain || step.edits().isEmpty())) return step;
        this.levelRoute = List.of();
        this.levelRouteGoal = null;
        return null;
    }

    private List<BlockPos> levelLine(BlockPos start, BlockPos goal) {
        List<BlockPos> route = new ArrayList<>();
        int currentX = start.getX();
        int currentZ = start.getZ();
        int distanceX = Math.abs(goal.getX() - currentX);
        int distanceZ = Math.abs(goal.getZ() - currentZ);
        int directionX = Integer.compare(goal.getX(), currentX);
        int directionZ = Integer.compare(goal.getZ(), currentZ);
        int error = distanceX - distanceZ;
        route.add(start);
        while (currentX != goal.getX() || currentZ != goal.getZ()) {
            int doubledError = error * 2;
            if (doubledError > -distanceZ) {
                error -= distanceZ;
                currentX += directionX;
            }
            if (doubledError < distanceX) {
                error += distanceX;
                currentZ += directionZ;
            }
            route.add(new BlockPos(currentX, start.getY(), currentZ));
        }
        return List.copyOf(route);
    }

    private Step greedyStep(Level level, RouteNode origin, BlockPos goal, boolean canModifyTerrain) {
        List<BlockPos> candidates = neighbors(origin.position(), goal);
        candidates.sort(Comparator.comparingDouble(candidate -> estimate(candidate, goal)));
        for (BlockPos candidate : candidates) {
            if (estimate(candidate, goal) >= origin.remainingDistance()) break;
            Step step = inspect(level, origin, candidate, goal);
            if (step != null && (canModifyTerrain || step.edits().isEmpty())) return step;
        }
        return null;
    }

    static BlockPos targetGroundPosition(LivingEntity target) {
        BlockPos feet = target.blockPosition();
        if (target.onGround()) return feet;
        Level level = target.level();
        for (int depth = 1; depth <= 4; depth++) {
            BlockPos support = feet.below(depth);
            if (!canEdit(level, support)) break;
            if (supports(level, support, level.getBlockState(support))) return support.above();
        }
        return feet;
    }

    boolean canWalkDirectlyTo(ProtectedEntity mob, LivingEntity target) {
        if (targetGroundPosition(target).getY() != mob.blockPosition().getY()) return false;
        Level level = mob.level();
        double offsetX = target.getX() - mob.getX();
        double offsetZ = target.getZ() - mob.getZ();
        int samples = Math.max(1, (int)Math.ceil(Math.sqrt(offsetX * offsetX + offsetZ * offsetZ) * 4.0D));
        double halfWidth = mob.getBbWidth() / 2.0D - 0.01D;
        for (int sample = 0; sample <= samples; sample++) {
            double progress = (double)sample / samples;
            double centerX = mob.getX() + offsetX * progress;
            double centerZ = mob.getZ() + offsetZ * progress;
            for (int cornerX = -1; cornerX <= 1; cornerX += 2) {
                for (int cornerZ = -1; cornerZ <= 1; cornerZ += 2) {
                    BlockPos support = BlockPos.containing(centerX + cornerX * halfWidth,
                            mob.getY() - 0.1D, centerZ + cornerZ * halfWidth);
                    if (!canEdit(level, support) || !canEdit(level, support.above(2))
                            || !supports(level, support, level.getBlockState(support))) return false;
                }
            }
            AABB body = mob.getBoundingBox().move(offsetX * progress, 0.0D, offsetZ * progress).deflate(0.001D);
            if (level.getBlockCollisions(mob, body).iterator().hasNext()) return false;
        }
        return true;
    }

    private Step forwardAscent(Level level, RouteNode origin, BlockPos goal, boolean canModifyTerrain) {
        BlockPos start = origin.position();
        if (goal.getY() <= start.getY()) return null;
        Step preferred = null;
        double preferredScore = Double.MAX_VALUE;
        for (BlockPos destination : neighbors(start, goal)) {
            if (destination.getY() != start.getY() + 1
                    || destination.getX() == start.getX() && destination.getZ() == start.getZ()) continue;
            if (estimate(destination, goal) >= estimate(start.above(), goal)) continue;
            Step step = inspect(level, origin, destination, goal);
            if (step == null || !canModifyTerrain && !step.edits().isEmpty()) continue;
            double score = estimate(destination, goal);
            if (score < preferredScore) {
                preferred = step;
                preferredScore = score;
            }
        }
        return preferred;
    }

    private Step descent(Level level, RouteNode origin, BlockPos goal, boolean canModifyTerrain) {
        BlockPos start = origin.position();
        if (goal.getY() >= start.getY()) return null;
        int bottom = Math.max(goal.getY(), start.getY() - MAX_DESCENT_HEIGHT);
        Step preferred = null;
        double preferredScore = origin.remainingDistance();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int height = start.getY() - 1; height >= bottom; height--) {
                    BlockPos destination = new BlockPos(start.getX() + offsetX, height, start.getZ() + offsetZ);
                    BlockPos support = destination.below();
                    if (!canEdit(level, support)) break;
                    if (!supports(level, support, stateAt(level, origin, List.of(), support))) continue;
                    Step step = inspect(level, origin, destination, goal);
                    if (step == null || !canModifyTerrain && !step.edits().isEmpty()) break;
                    double score = estimate(destination, goal);
                    if (score < preferredScore) {
                        preferred = step;
                        preferredScore = score;
                    }
                    break;
                }
            }
        }
        return preferred;
    }

    List<BlockPos> previewRunway(ProtectedEntity mob, LivingEntity target, Step activeStep, int length) {
        BlockPos goal = targetGroundPosition(target);
        if (activeStep.from().getY() != activeStep.destination().getY()
                || !goal.equals(this.levelRouteGoal)) return List.of();
        int currentIndex = this.levelRoute.indexOf(activeStep.destination());
        if (currentIndex < 1 || !this.levelRoute.get(currentIndex - 1).equals(activeStep.from())) return List.of();
        Map<BlockPos, BlockState> plannedBlocks = new HashMap<>();
        for (Edit edit : activeStep.edits()) plannedBlocks.put(edit.position(), edit.result());
        RouteNode current = new RouteNode(activeStep.destination(), 0.0D,
                estimate(activeStep.destination(), goal), null, null, plannedBlocks);
        List<BlockPos> runway = new ArrayList<>();
        int lastIndex = Math.min(this.levelRoute.size() - 1, currentIndex + length);
        for (int index = currentIndex + 1; index <= lastIndex; index++) {
            Step step = inspect(mob.level(), current, this.levelRoute.get(index), goal);
            if (step == null || step.edits().stream().anyMatch(edit -> edit.action() != Action.PLACE)) break;
            for (Edit edit : step.edits()) {
                plannedBlocks.put(edit.position(), edit.result());
                runway.add(edit.position());
            }
            current = new RouteNode(step.destination(), 0.0D,
                    estimate(step.destination(), goal), null, null, plannedBlocks);
        }
        return runway;
    }

    private List<BlockPos> neighbors(BlockPos current, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>(26);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = current.relative(direction);
            result.add(adjacent);
            result.add(adjacent.above());
            result.add(adjacent.below());
        }
        for (int offsetX = -1; offsetX <= 1; offsetX += 2) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ += 2) {
                BlockPos adjacent = current.offset(offsetX, 0, offsetZ);
                result.add(adjacent);
                result.add(adjacent.above());
                result.add(adjacent.below());
            }
        }
        if (goal.getY() > current.getY()) result.add(current.above());
        if (goal.getY() < current.getY()) result.add(current.below());
        return result;
    }

    private Step inspect(Level level, RouteNode current, BlockPos destination, BlockPos goal) {
        BlockPos from = current.position();
        List<Edit> edits = new ArrayList<>();
        if (!canEdit(level, destination.below()) || !canEdit(level, destination.above())) return null;
        if (destination.getY() > from.getY()
                && !clear(level, current, edits, from.above(2))) return null;
        if (destination.getY() < from.getY()) {
            for (int height = from.getY() + 1; height > destination.getY() + 1; height--) {
                if (!clear(level, current, edits, new BlockPos(destination.getX(), height, destination.getZ()))) return null;
            }
        }
        if (!clear(level, current, edits, destination)
                || !clear(level, current, edits, destination.above())) return null;

        if (destination.getX() != from.getX() && destination.getZ() != from.getZ()) {
            BlockPos cornerX = new BlockPos(destination.getX(), destination.getY(), from.getZ());
            BlockPos cornerZ = new BlockPos(from.getX(), destination.getY(), destination.getZ());
            for (BlockPos corner : List.of(cornerX, cornerZ)) {
                for (int height = destination.getY(); height <= Math.max(from.getY(), destination.getY()) + 1; height++) {
                    if (!clear(level, current, edits, new BlockPos(corner.getX(), height, corner.getZ()))) return null;
                }
                if (destination.getY() >= from.getY()
                        && !support(level, current, edits, corner.below(), goal)) return null;
            }
        }

        if (!support(level, current, edits, destination.below(), goal)) return null;
        return new Step(from, destination, List.copyOf(edits));
    }

    private boolean support(Level level, RouteNode current, List<Edit> edits, BlockPos support, BlockPos goal) {
        if (!canEdit(level, support)) return false;
        BlockState floor = stateAt(level, current, edits, support);
        if (!supports(level, support, floor)) {
            int destinationY = support.getY() + 1;
            int fromY = current.position().getY();
            if (destinationY < fromY || destinationY > fromY && goal.getY() <= fromY
                    || !floor.canBeReplaced() || floor.hasBlockEntity()) return false;
            if (!floor.getFluidState().isEmpty()) edits.add(new Edit(support, Action.DRAIN));
            edits.add(new Edit(support, Action.PLACE));
        }
        return true;
    }

    private boolean clear(Level level, RouteNode current, List<Edit> edits, BlockPos pos) {
        if (!canEdit(level, pos)) return false;
        BlockState state = stateAt(level, current, edits, pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            if (state.getDestroySpeed(level, pos) < 0.0F || state.hasBlockEntity()) return false;
            edits.add(new Edit(pos, Action.BREAK));
        }
        if (!state.getFluidState().isEmpty()) {
            if (state.hasBlockEntity()) return false;
            edits.add(new Edit(pos, Action.DRAIN));
        }
        return true;
    }

    private BlockState stateAt(Level level, RouteNode current, List<Edit> edits, BlockPos pos) {
        for (int index = edits.size() - 1; index >= 0; index--) {
            Edit edit = edits.get(index);
            if (edit.position().equals(pos)) return edit.result();
        }
        BlockState planned = current.plannedBlocks().get(pos);
        return planned != null ? planned : level.getBlockState(pos);
    }

    static boolean canEdit(Level level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.isLoaded(pos)
                && level.getWorldBorder().isWithinBounds(pos);
    }

    static boolean supports(Level level, BlockPos pos, BlockState state) {
        return state.isFaceSturdy(level, pos, Direction.UP) || state.getFluidState().is(FluidTags.WATER);
    }

    private static double estimate(BlockPos position, BlockPos goal) {
        double offsetX = position.getX() - goal.getX();
        double offsetY = position.getY() - goal.getY();
        double offsetZ = position.getZ() - goal.getZ();
        return Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
    }

    record Step(BlockPos from, BlockPos destination, List<Edit> edits) {
        boolean isPillar() {
            return this.destination.equals(this.from.above());
        }
    }

    record Edit(BlockPos position, Action action) {
        BlockState result() {
            return this.action == Action.PLACE ? Blocks.COBBLESTONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
    }

    enum Action {
        DRAIN(2.0D), BREAK(3.0D), PLACE(4.0D);

        private final double cost;

        Action(double cost) {
            this.cost = cost;
        }
    }

    private record RouteNode(BlockPos position, double cost, double remainingDistance,
                             RouteNode previous, Step step, Map<BlockPos, BlockState> plannedBlocks) {
    }
}
