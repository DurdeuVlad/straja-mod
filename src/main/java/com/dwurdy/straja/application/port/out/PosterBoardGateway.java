package com.dwurdy.straja.application.port.out;

/**
 * POSTER-1: the physical poster surface — display entities a service
 * drives headlessly. The service decides what a spot shows and when it
 * swaps; the adapter owns how the face and caption materialize in the
 * world (entities, stacks, text).
 */
public interface PosterBoardGateway {

    /**
     * Shows the wanted face + caption at the spot. Implementations spawn
     * or repair the spot's entities idempotently — calling render on an
     * already-correct spot must never duplicate entities.
     */
    void renderFace(String spotId, String dimension, double x, double y, double z,
                    String targetUuid, String targetName, int reward);

    /** Shows the idle "nothing hunted" state at the spot. */
    void renderIdle(String spotId, String dimension, double x, double y, double z);

    /** Tears the spot's entities down. */
    void remove(String spotId, String dimension, double x, double y, double z);

    /** Players currently close enough to read this spot. */
    int watchersNear(String dimension, double x, double y, double z, double radius);

    PosterBoardGateway NONE = new PosterBoardGateway() {
        @Override public void renderFace(String spotId, String dimension,
                double x, double y, double z, String targetUuid,
                String targetName, int reward) {}
        @Override public void renderIdle(String spotId, String dimension,
                double x, double y, double z) {}
        @Override public void remove(String spotId, String dimension,
                double x, double y, double z) {}
        @Override public int watchersNear(String dimension,
                double x, double y, double z, double radius) { return 0; }
    };
}
