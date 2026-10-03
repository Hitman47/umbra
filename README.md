# Nyxara

App Android perso (téléphone / tablette) pour lire les vidéos d'un ou plusieurs NAS, chez soi ou à distance via Tailscale. Anciennement Umbra : l'identifiant Android (`io.github.mkdevtests.umbra`) ne change pas, les mises à jour et les données sont conservées.

- Lecteur intégré basé sur libmpv : 4K HDR, Dolby Vision, DTS / TrueHD, sous-titres ASS / PGS.
- Accès aux NAS en SMB, en lecture seule, via un mini-serveur HTTP local ; plusieurs sources, dossiers exclus.
- Bibliothèque style Infuse : métadonnées TMDB (TheTVDB pour la numérotation des animes), fiches avec distribution, sagas et titres liés.
- Suivi Trakt : synchronisation et scrobble.
- Mesures de lecture dans Réglages › Lecture. Comparaison SMB / WebDAV / NFS : `scripts/protocol_bench.py` (PC).

Construire et installer : `./scripts/build-nyxara-debug.sh` (Git Bash). Publier : `./scripts/publish-nyxara-release.sh` depuis `main`.

Cahier des charges : https://claude.ai/code/artifact/6b86ab47-e8ab-4b93-8e1b-74a8d50458da
