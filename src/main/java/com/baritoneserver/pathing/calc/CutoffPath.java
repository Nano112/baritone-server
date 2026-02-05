/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.baritoneserver.pathing.calc;

import com.baritoneserver.api.pathing.calc.IPath;
import com.baritoneserver.api.pathing.goals.Goal;
import com.baritoneserver.api.utils.BetterBlockPos;

import java.util.Collections;
import java.util.List;

/**
 * A path that is a subset of another path, created by cutting off positions
 * beyond a certain index. Simplified for server-side use (no Movement tracking yet).
 */
public class CutoffPath implements IPath {

    private final List<BetterBlockPos> path;

    private final int numNodes;

    private final Goal goal;

    public CutoffPath(IPath prev, int firstPositionToInclude, int lastPositionToInclude) {
        path = prev.positions().subList(firstPositionToInclude, lastPositionToInclude + 1);
        numNodes = prev.getNumNodesConsidered();
        goal = prev.getGoal();
        sanityCheck();
    }

    public CutoffPath(IPath prev, int lastPositionToInclude) {
        this(prev, 0, lastPositionToInclude);
    }

    @Override
    public IPath postProcess() {
        // Already processed via parent path
        return this;
    }

    @Override
    public IPath cutoffAtLoadedChunks(Object bsi) {
        // Already cut off
        return this;
    }

    @Override
    public IPath staticCutoff(Goal destination) {
        // Already cut off
        return this;
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public List<BetterBlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }
}
