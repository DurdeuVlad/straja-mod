package com.dwurdy.straja.adapter.in.command;

import java.util.List;

/** Structured, permission-filtered command help metadata. */
public record HelpSpec(
        String syntax,
        int permissionLevel,
        String domainAuthorization,
        String purpose,
        String sideEffects,
        List<String> persistentRecords,
        List<String> errorCodes,
        List<String> examples,
        String recoveryCommand,
        boolean sensitive,
        String section) {

    public HelpSpec(String syntax, String purpose, int permissionLevel, String section) {
        this(syntax, permissionLevel, "", purpose, "", List.of(), List.of(), List.of(), null,
                permissionLevel >= CommandPermissions.ADMIN, section);
    }

    /** Compatibility aliases keep the help renderer compact while metadata is structured. */
    public int permission() { return permissionLevel; }
    public String description() { return purpose; }
}
