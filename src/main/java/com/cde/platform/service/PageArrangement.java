package com.cde.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A requested page layout, and what it does to the document it is applied to.
 *
 * <p>Reordering, deleting, duplicating and rotating are all the same
 * operation seen from different angles — a list saying which source pages
 * appear, in what order, at what rotation. Modelling them as one arrangement
 * rather than four commands means the page organiser can send a single
 * request for a batch of edits, and the version history gets one entry
 * describing the net effect instead of one per drag.
 *
 * <p>Pure: it holds no services and touches no files, so the summary logic
 * that ends up in front of users is directly testable.
 */
public record PageArrangement(List<PageRef> pages) {

    /**
     * One page in the result.
     *
     * @param source   document the page comes from, or null for the document
     *                 being rearranged
     * @param page     1-based page number within that document
     * @param rotate   degrees to turn it, relative to its current rotation
     */
    public record PageRef(String source, int page, int rotate) {

        public PageRef {
            rotate = Math.floorMod(rotate, 360);
        }

        public static PageRef of(int page) {
            return new PageRef(null, page, 0);
        }

        /** True when this page came from the document being rearranged. */
        public boolean isOwn() {
            return source == null;
        }
    }

    /** The arrangement that leaves a document of {@code pageCount} unchanged. */
    public static PageArrangement identity(int pageCount) {
        List<PageRef> pages = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) pages.add(PageRef.of(page));
        return new PageArrangement(pages);
    }

    public boolean isEmpty() {
        return pages == null || pages.isEmpty();
    }

    public int size() {
        return pages.size();
    }

    /** Shape the converter's {@code /rearrange-pages} endpoint expects. */
    public List<Map<String, Object>> toPlan() {
        return pages.stream()
            .map(ref -> {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("source", ref.source());
                entry.put("page",   ref.page());
                entry.put("rotate", ref.rotate());
                return entry;
            })
            .collect(Collectors.toList());
    }

    /**
     * Describes this arrangement as a change to a document that currently has
     * {@code pageCount} pages, for the version history.
     *
     * @return e.g. "Deleted 2 pages, rotated 1 page, reordered pages"
     */
    public String describeChangeFrom(int pageCount) {
        List<String> changes = new ArrayList<>();

        int removed = countRemoved(pageCount);
        if (removed > 0) changes.add(plural(removed, "Deleted %d page", "Deleted %d pages"));

        int inserted = (int) pages.stream().filter(ref -> !ref.isOwn()).count();
        if (inserted > 0) changes.add(plural(inserted, "Inserted %d page", "Inserted %d pages"));

        int duplicated = countDuplicated();
        if (duplicated > 0) {
            changes.add(plural(duplicated, "Duplicated %d page", "Duplicated %d pages"));
        }

        int rotated = (int) pages.stream().filter(ref -> ref.rotate() != 0).count();
        if (rotated > 0) changes.add(plural(rotated, "Rotated %d page", "Rotated %d pages"));

        if (isReordered()) changes.add("Reordered pages");

        if (changes.isEmpty()) return "No page changes";

        // Only the first change keeps its capital — the rest read as a list.
        String first = changes.get(0);
        String rest = changes.stream().skip(1)
            .map(change -> Character.toLowerCase(change.charAt(0)) + change.substring(1))
            .collect(Collectors.joining(", "));
        return rest.isEmpty() ? first : first + ", " + rest;
    }

    /** Pages of the original document that this arrangement drops. */
    private int countRemoved(int pageCount) {
        Set<Integer> kept = pages.stream()
            .filter(PageRef::isOwn)
            .map(PageRef::page)
            .collect(Collectors.toSet());
        return (int) java.util.stream.IntStream.rangeClosed(1, pageCount)
            .filter(page -> !kept.contains(page))
            .count();
    }

    /** Extra copies beyond the first appearance of each own page. */
    private int countDuplicated() {
        List<Integer> own = pages.stream().filter(PageRef::isOwn).map(PageRef::page).toList();
        return own.size() - new LinkedHashSet<>(own).size();
    }

    /** True when the retained pages appear out of their original order. */
    private boolean isReordered() {
        List<Integer> own = pages.stream()
            .filter(PageRef::isOwn)
            .map(PageRef::page)
            .distinct()
            .toList();
        return !own.equals(own.stream().sorted().toList());
    }

    private static String plural(int count, String singular, String plural) {
        return String.format(count == 1 ? singular : plural, count);
    }
}
