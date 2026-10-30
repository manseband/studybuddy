package studybuddy.application;

import studybuddy.domain.Material;

/**
 * The uploaded file has the same content as a material already in the course (F01).
 * The caller asks the student to Replace or Skip and, for Replace, uploads again with replace = true.
 */
public class DuplicateMaterialException extends RuntimeException {

    private final transient Material existing;

    public DuplicateMaterialException(Material existing) {
        super("This file is already in the course as \"" + existing.getTitle() + "\"");
        this.existing = existing;
    }

    public Material getExisting() {
        return existing;
    }
}
