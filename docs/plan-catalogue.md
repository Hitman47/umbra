# Plan : vue « Profils » de l'onglet Perso (catalogue externe)

État : **fait** (1.1.0), à vérifier sur appareil.
`catalog/Catalog.kt`, `catalog/CatalogClient.kt`, `catalog/CatalogStore.kt`,
route photo dans `nas/LocalStreamServer.kt`.

## Quoi

Dans l'onglet Perso, un sélecteur en haut : **Dossiers** (l'actuel) | **Profils**.

- **Grille des profils**, sur le modèle du catalogue externe : photo, nom, catégorie,
  « N vidéos · X Go » (seulement ce qui est sur le NAS), étoile si suivi.
  Recherche (nom + alias), filtres Catégorie / Suivis, tri (nom, vidéos, taille).
  Une carte **Groupes** à part pour les vidéos sans profil.
- **Fiche d'un profil** : photo, nom, alias, tout le profil fourni par le catalogue
  (Nyxara n'enrichit rien lui-même), texte, puis **Aléatoire** / **Dans l'ordre**,
  puis la liste de ses vidéos (nom, taille, durée, vignette si une image est à côté).
- **Sélection** : appui long → cases à cocher → « Lire la sélection » (dans l'ordre
  ou aléatoire). Liste temporaire, juste pour la lecture.

## Comment

1. **Réglages › Perso › Catalogue externe** : adresse maison
   (`http://192.168.1.131:8086`), adresse hors de chez soi (`http://100.92.1.1:8086`),
   identifiant / mot de passe facultatifs (chiffrés, comme ceux des NAS), rythme de
   synchro : à chaque lancement / une fois par jour (défaut) / à la main, bouton
   « Synchroniser », état (nombre de profils et vidéos, date, erreur).
2. **Synchro** (`CatalogStore.sync`) : lecture seule, uniquement des GET sur
   `/api/v1` : profils par pages de 500, puis toutes les vidéos par pages de 500.
   Rangée sur l'appareil (`noBackupFilesDir/catalog.json`), hors sauvegarde.
   Lancée au démarrage selon le rythme choisi (`syncIfDue`).
3. **Lien vidéo ↔ profil** (`CatalogIndex`) : une vidéo appartient au profil qui
   possède le dossier parent le plus proche ; sinon → Groupes. Pas besoin d'un
   champ spécifique du serveur.
4. **Chemins** : le catalogue donne des chemins relatifs à sa racine ; sa racine est
   exactement un dossier Perso. Elle est détectée automatiquement à la synchro
   (quelques vidéos testées sous chaque dossier Perso). Chemin NAS = racine + chemin.
5. **Photos** : servies par le serveur local de l'appli (`/rimg/…`, clé
   `p/<id>/<version>`), qui les demande au catalogue (maison puis hors de chez soi,
   avec l'identifiant). L'URL reste la même quel que soit le réseau : le cache
   d'images la garde, hors connexion compris. La version change quand la photo change.
6. **Fiche** : `GET /performers/<id>` à l'ouverture, gardée sur l'appareil pour
   l'afficher hors connexion. Les champs connus ont un libellé français, les autres
   sont affichés tels que le catalogue les donne.
7. **Lecture** : même lecteur, même file d'attente, même reprise et historique Perso
   (ce sont les mêmes fichiers). `PersoRequest` reçoit l'id du profil (ou
   « groupes ») et éventuellement la sélection ; le lecteur prend les chemins dans
   le catalogue local, sans parcourir le NAS. Rien vers Trakt ni la bibliothèque.
8. **Verrou** : le code / l'empreinte de Perso couvre aussi les Profils.

## Pourquoi ainsi

- **Lecture seule** : Nyxara ne modifie jamais rien, ni sur le NAS, ni sur Trakt,
  ni sur le catalogue. Un test (`CatalogReadOnlyTest`) vérifiera que le client
  n'envoie que des GET.
- **Copie locale** : l'onglet s'ouvre instantanément, même hors connexion, et
  1 240 profils + des milliers de vidéos tiennent en quelques Mo.
- **Proxy photos** : authentification et changement d'adresse gérés à un seul
  endroit, cache stable.
- **Discrétion** : vocabulaire neutre dans le code (catalogue, profils, groupes) ;
  titres de commits neutres (« Intégrations tierces améliorées ») car les notes de
  version sont faites à partir des titres de commits.

## Reste à faire

1. Brancher `CatalogStore` dans `NyxaraApp` (`catalog`, `streamServer.remoteImages`,
   `syncIfDue()` au lancement).
2. Section Réglages › Perso › Catalogue externe.
3. Écrans : sélecteur Dossiers | Profils, grille, fiche, sélection multiple.
4. `PersoRequest` (profil / sélection) et `preparePerso` qui prend les chemins du
   catalogue.
5. Tests JVM : `CatalogIndex` (rattachement, Groupes, chemins), `parseProfile`,
   `CatalogReadOnlyTest`.
6. Version 1.1.0, commit au titre neutre, CI.
