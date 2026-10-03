# Nyxara

App Android perso (téléphone / tablette) pour lire les vidéos d'un ou plusieurs NAS, chez soi ou à distance via Tailscale. Anciennement Umbra : l'identifiant Android (`io.github.mkdevtests.umbra`) ne change pas, les mises à jour et les données sont conservées.

- Lecteur intégré basé sur libmpv : 4K HDR, Dolby Vision, DTS / TrueHD, sous-titres ASS / PGS.
- Accès aux NAS en SMB, NFS ou WebDAV, en lecture seule : SMB et NFS via un mini-serveur HTTP local, WebDAV lu directement par le lecteur ; plusieurs sources, dossiers exclus.
- Bibliothèque style Infuse : métadonnées TMDB (TheTVDB pour la numérotation des animes), fiches avec distribution, sagas et titres liés.
- Suivi Trakt : synchronisation et scrobble.
- Mesures de lecture dans Réglages › Lecture.

Construire et installer : `./scripts/build-nyxara-debug.sh` (Git Bash). Publier : `./scripts/publish-nyxara-release.sh` depuis `main`.

Cahier des charges : https://claude.ai/code/artifact/6b86ab47-e8ab-4b93-8e1b-74a8d50458da
