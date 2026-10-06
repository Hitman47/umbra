# Plan : 3 builds (1.2.0, 1.3.0, 1.4.0)

## Build A — 1.2.0 : fiabilité
1. **Écran noir au lancement** (ex. un épisode MKV H.264 + E-AC-3, 22 sous-titres) :
   le lecteur garde un journal pendant l'ouverture. Si l'image ne démarre pas
   (fin du fichier avant la 1re image, ou rien après 25 s), un panneau
   « Lecture impossible » affiche les erreurs du lecteur, les codecs et le
   décodeur, avec « Copier » pour me les envoyer.
2. **Analyse des gros partages (≈ 50 000 fichiers)** :
   - un dossier qui ne répond pas n'arrête plus tout : réessayé à la fin, sinon
     ses titres d'avant sont gardés (comme un NAS injoignable) ;
   - journal de reprise : les dossiers déjà lus sont gardés sur l'appareil, une
     analyse interrompue reprend sans tout relire (12 h) ;
   - réponses TMDB gardées un jour : la reconnaissance reprend vite ;
   - service au premier plan avec notification : Android ne coupe plus l'analyse.
3. **Textes sur téléphone** : en-têtes des rangées (titre au-dessus, choix en
   dessous), rangées de boutons qui passent à la ligne.

## Build B — 1.3.0 : navigation et classement
4. **Saisons** : résumé, date, note, affiche de chaque saison sur la fiche (déjà
   reçus de TMDB à l'analyse, désormais gardés).
5. **Étagères → grilles** : le titre de chaque rangée de l'accueil ouvre la grille
   complète (défilement continu, filtres et tris de la grille Films/Séries).
6. **Séries | Animation | Animés** : puces dans l'onglet Séries, classement auto
   d'après TMDB (genre Animation + pays/langue d'origine Japon).
7. **Documentaires** : dossiers désignés dans les Réglages ; leurs titres quittent
   Films et Séries pour un onglet Documentaires (filtres Films/Séries).
   Barre du bas : Accueil, Films, Séries, Docs, Perso ; Recherche et Dossiers
   passent en icônes en haut.

## Build C — 1.4.0 : titres absents et recherche
8. **Fiche d'un titre absent** (depuis les titres similaires) : fiche TMDB sans
   lecture, gardée en cache (200 fiches, 7 jours).
9. **Recherche Prowlarr** (adresse + clé API dans les Réglages) : les résultats
   s'affichent (nom, taille, seeders, indexeur ; 1080p mis en avant), je choisis.
10. **Envoi à qBittorrent** (adresse + identifiants) : le lien choisi est ajouté ;
    le titre passe en « Demandé » sur sa fiche jusqu'à son arrivée dans la
    bibliothèque (ou 30 jours).
