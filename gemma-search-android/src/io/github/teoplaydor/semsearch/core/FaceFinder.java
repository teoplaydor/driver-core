package io.github.teoplaydor.semsearch.core;

import java.util.List;

/** Finds the faces of an image with their vectors (FaceModel; a stand-in in tests). */
public interface FaceFinder extends AutoCloseable {
    /** Faces of an ARGB image (w×h) with unit vectors, best first; smaller than {@code minSize} pixels left out. */
    List<FaceModel.Face> faces(int[] argb, int w, int h, int minSize) throws Exception;

    @Override
    void close();
}
