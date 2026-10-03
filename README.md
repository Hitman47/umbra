# Nyxara

App Android perso (téléphone / tablette) pour lire les vidéos d'un ou plusieurs NAS, chez soi ou à distance via Tailscale. Anciennement Umbra : l'identifiant Android (`io.github.mkdevtests.umbra`) ne change pas, les mises à jour et les données sont conservées.

- Lecteur intégré basé sur libmpv : 4K HDR, Dolby Vision, DTS / TrueHD, sous-titres ASS / PGS ; avance / recul au geste (double appui 10 s, maintien de ⏪ ⏩, glisser sur l'image), intro et génériques à passer (chapitres), épisode suivant au générique, décalage audio, image dans l'image, son en arrière-plan.
- Sous-titres en ligne (OpenSubtitles) dans les langues choisies, le meilleur en un geste et gardé avec la vidéo : clé `opensubtitles.key` dans `local.properties`.
- Accès aux NAS en SMB, NFS ou WebDAV, en lecture seule : SMB et NFS via un mini-serveur HTTP local, WebDAV lu directement par le lecteur ; plusieurs sources, dossiers exclus.
- Bibliothèque style Infuse : métadonnées TMDB (TheTVDB pour la numérotation des animes), fiches avec distribution, sagas, « Aussi avec Batman » (même personnage), même réalisateur et titres liés ; puce Univers (films reliés par leurs personnages) ; qualité, langues et sous-titres lus dans les fichiers (MKV, MP4) ; jaquettes du NAS (folder.jpg, poster.jpg, fanart.jpg…) ; raccourcis de dossiers à l'accueil ; corrections de matching listées dans Réglages ; filtres vus / genre / années ; appui long pour marquer vu ou non vu.
- Suivi Trakt : synchronisation et scrobble.
- Mesures de lecture dans Réglages › Lecture.

Construire et installer : `./scripts/build-nyxara-debug.sh` (Git Bash). Publier : `./scripts/publish-nyxara-release.sh` depuis `main`.

Cahier des charges : https://claude.ai/code/artifact/6b86ab47-e8ab-4b93-8e1b-74a8d50458da
