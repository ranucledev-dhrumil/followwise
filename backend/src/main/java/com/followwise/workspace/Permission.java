package com.followwise.workspace;

import java.util.Set;

public enum Permission {
    VIEW_WORKSPACE(Set.of(Role.OWNER, Role.STAFF)),
    VIEW_MEMBERS(Set.of(Role.OWNER)),
    VIEW_APP(Set.of(Role.OWNER, Role.STAFF)),
    MANAGE_APPS(Set.of(Role.OWNER)),
    VIEW_DATA(Set.of(Role.OWNER, Role.STAFF)),
    EDIT_DATA(Set.of(Role.OWNER, Role.STAFF)),
    VIEW_LEDGER(Set.of(Role.OWNER, Role.STAFF)),
    RECORD_PAYMENT(Set.of(Role.OWNER, Role.STAFF)),
    MANAGE_LEDGER(Set.of(Role.OWNER)),
    VIEW_DOCUMENTS(Set.of(Role.OWNER, Role.STAFF)),
    EDIT_DOCUMENTS(Set.of(Role.OWNER, Role.STAFF)),
    MANAGE_DOCUMENTS(Set.of(Role.OWNER)),
    VIEW_REMINDERS(Set.of(Role.OWNER, Role.STAFF));

    private final Set<Role> grantedRoles;

    Permission(Set<Role> grantedRoles) {
        this.grantedRoles = grantedRoles;
    }

    public boolean isGrantedTo(Role role) {
        return role != null && grantedRoles.contains(role);
    }
}
