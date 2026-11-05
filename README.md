# Voxbulle

App Android de dictée vocale : une bulle flottante, on parle, le texte transcrit (Voxtral, Mistral) est prêt à coller.

Code à venir : intégration du PoC (DMC-142).

## Règles du repo

- Repo privé. Pas de GitHub Actions : tests en local et sur le NAS.
- `main` protégée : PR obligatoire, fusion par squash uniquement (commit signé par GitHub), pas de force-push ni de suppression.
- Fusion par Quinn ou Victor après leurs deux approbations dans Paperclip. Micka ne fusionne pas.
- Aucune clé dans le repo : la clé Mistral reste hors de git (`local.properties` ou variable d'environnement).
