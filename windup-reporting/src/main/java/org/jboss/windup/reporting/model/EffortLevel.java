package org.jboss.windup.reporting.model;

/**
 * Represents the estimated effort level to resolve a migration issue,
 * expressed in story points.
 */
public enum EffortLevel {

    UNKNOWN(0),
    TRIVIAL(1),
    COMPLEX(3),
    REDESIGN(5),
    ARCHITECTURAL(7);

    private final int storyPoints;

    EffortLevel(int storyPoints) {
        this.storyPoints = storyPoints;
    }

    /**
     * Returns the number of story points associated with this effort level.
     */
    public int getStoryPoints() {
        return storyPoints;
    }

    /**
     * Returns the {@link EffortLevel} whose story-point value matches the given number,
     * or {@link #UNKNOWN} if no match is found.
     */
    public static EffortLevel fromStoryPoints(int storyPoints) {
        for (EffortLevel level : values()) {
            if (level.storyPoints == storyPoints) {
                return level;
            }
        }
        return UNKNOWN;
    }
}
