package dev.wp.matter_manipulator.common.items.manipulator;

public enum PendingAction {
    GEOM_SELECTING_BLOCK,
    MARK_COPY_A,
    MARK_COPY_B,
    MARK_CUT_A,
    MARK_CUT_B,
    MARK_PASTE,
    EXCH_ADD_REPLACE,
    EXCH_SET_REPLACE,
    EXCH_SET_TARGET,
    PICK_CABLE,
    /** Waiting for the user to right-click to set corner B after corner A was anchored. */
    MOVING_COORDS;

    public boolean isDirectBlockPick() {
        return this == GEOM_SELECTING_BLOCK
            || this == EXCH_ADD_REPLACE
            || this == EXCH_SET_REPLACE
            || this == PICK_CABLE;
    }
}
