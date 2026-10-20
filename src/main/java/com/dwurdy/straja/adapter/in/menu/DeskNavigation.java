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
    private DeskPageSource current = new DeskPageSource.RolePage(0);

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

    /** Pops to the previous page, or the role grid when the trail is empty. */
    DeskPageSource back() {
        current = back.isEmpty() ? new DeskPageSource.RolePage(0) : back.pop();
        return current;
    }

    /** {@code main:} semantics — drops the whole trail back to the role grid. */
    DeskPageSource home() {
        back.clear();
        current = new DeskPageSource.RolePage(0);
        return current;
    }

    /**
     * Re-pages the current role grid or section drawer in place; other
     * sources have no paging.
     */
    DeskPageSource goToPage(int page) {
        if (current instanceof DeskPageSource.RolePage) {
            current = new DeskPageSource.RolePage(Math.max(0, page));
        } else if (current instanceof DeskPageSource.SectionPage section) {
            current = new DeskPageSource.SectionPage(section.section(), Math.max(0, page));
        }
        return current;
    }
}
