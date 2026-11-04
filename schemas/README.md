# Schemas

Per **D-61**, the canonical App Definition JSON schema and launch template files live under:
`backend/src/main/resources/appdefinition/`
- Schema: `backend/src/main/resources/appdefinition/app-definition-v1.schema.json`
- Templates: `backend/src/main/resources/appdefinition/templates/`
  - `rental-property-manager.json`
  - `client-document-tracker.json`

This ensures Render builds directly from `backend/` without requiring repo-root resources.
