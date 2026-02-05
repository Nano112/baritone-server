package com.baritoneserver.pathing.movement;

/**
 * Mutable result holder for movement cost calculations.
 * Avoids allocating new objects for each move evaluation in the A* loop.
 */
public class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public MutableMoveResult() {
        reset();
    }

    public void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = 0;
    }
}
