package com.dwurdy.straja.adapter.in.menu;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Hub-and-spoke navigation stack for a desk session: every page stores its
 * page-source so «Înapoi» and a cancelled confirm return to the page the
 * player came from instead of dropping context (reclame.js gap 2).
 * Pure — unit-tested without Minecraft classes.
 */
final class DeskNavigation {
    private final Deque<DeskPageSource> back = new ArrayDeque<>();
    private final DeskPageSource root;
    private DeskPageSource current;

    DeskNavigation() {
        this(new DeskPageSource.RolePage(0));
    }

    /**
     * Tool and bound-surface sessions anchor on a non-role root — «Înapoi»
     * and home land on it instead of the role grid.
     */
    DeskNavigation(DeskPageSource root) {
        this.root = root == null ? new DeskPageSource.RolePage(0) : root;
        this.current = this.root;
    }

    DeskPageSource current() {
        return current;
    }

    /** How many pages sit below the current one — controls the «Înapoi» chrome. */
    int depth() {
        return back.size();
    }

    /** Pushes the current page onto the trail and shows {@code next}. */
    DeskPageSource open(DeskPageSource next) {
        back.push(current);
        current = next;
        return current;
    }

    /** Pops to the previous page, or the session root when the trail is empty. */
    DeskPageSource back() {
        current = back.isEmpty() ? root : back.pop();
        return current;
    }

    /** {@code main:} semantics — drops the whole trail back to the root. */
    DeskPageSource home() {
        back.clear();
        current = root;
        return current;
    }

    /**
     * Re-pages the current role grid, section drawer, tool list or bound
     * surface in place; other sources have no paging.
     */
    DeskPageSource goToPage(int page) {
        if (current instanceof DeskPageSource.RolePage) {
            current = new DeskPageSource.RolePage(Math.max(0, page));
        } else if (current instanceof DeskPageSource.SectionPage section) {
            current = new DeskPageSource.SectionPage(section.section(), Math.max(0, page));
        } else if (current instanceof DeskPageSource.ListPage list) {
            current = new DeskPageSource.ListPage(list.title(), list.items(), Math.max(0, page));
        } else if (current instanceof DeskPageSource.SurfacePage surface) {
            current = new DeskPageSource.SurfacePage(surface.binding(), Math.max(0, page));
        } else if (current instanceof DeskPageSource.DynamicPage dynamic) {
            current = new DeskPageSource.DynamicPage(
                    dynamic.kind(), dynamic.id(), Math.max(0, page));
        }
        return current;
    }
}
