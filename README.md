# Chess2PGN

Application Android qui analyse la vidéo d'une partie d'échecs sur un vrai
échiquier et la reconstitue en fichier PGN.

## Principe

L'app ne reconnaît jamais les pièces individuellement (pas de ML). Elle repose
sur trois idées :

1. **Redressement du plateau** : tu pointes les 4 coins sur la première image,
   une transformation perspective (`Matrix.setPolyToPoly`) ramène le plateau
   à une grille 8×8 plate.
2. **Déclenchement par la pendule** : tu délimites aussi la zone de la
   pendule. Un coup n'est recherché qu'après un appui détecté (pic de
   changement dans la zone quand la main passe, puis retour au calme) —
   les mains qui traînent au-dessus du plateau entre deux appuis sont
   ignorées. Une fois l'appui détecté, on attend que le plateau soit
   immobile, puis on compare chaque case (couleur moyenne + texture) avec
   la dernière position validée.
3. **Inférence par les règles** : comme la position de départ est connue,
   chaque ensemble de cases modifiées est confronté aux coups **légaux**
   (lib `chesslib`). Le coup dont l'effet colle le mieux est retenu — ce qui
   gère automatiquement roques, prises en passant et promotions. Si un état
   intermédiaire a été raté (coups rapides), l'app essaie aussi les paires de
   coups consécutifs.

## Compilation (GitHub Actions)

Comme d'habitude :

1. Pousser ce dossier dans un repo GitHub.
2. Onglet **Actions** → workflow *Build APK* (se lance à chaque push, ou
   manuellement via *Run workflow*).
3. Télécharger l'artifact `chess2pgn-debug-apk` et installer l'APK.

## Protocole de tournage (important !)

- **Caméra fixe** (trépied ou téléphone calé), plateau **entièrement visible**,
  idéalement vu du dessus ou en plongée marquée.
- La vidéo doit **commencer sur la position initiale**, pièces déjà en place.
- Éclairage **constant** (pas d'ombre qui balaie le plateau, pas de soleil
  direct changeant).
- La pendule doit être visible dans le champ, et chaque coup doit être suivi
  d'un appui sur la pendule (l'appui de lancement de la partie est ignoré
  automatiquement).
- Après l'appui, laisser le plateau visible et immobile ~1 s.
- Poser les pièces capturées **hors du plateau**.

## Limites connues

- Promotion : la pièce choisie n'est pas visible pour l'algo → **dame par
  défaut** (corriger à la main dans le PGN si sous-promotion).
- Si un coup est définitivement inexpliqué, il est ignoré et signalé avec son
  horodatage dans l'écran de résultat — les coups suivants risquent alors
  d'échouer en cascade. Le timestamp permet de retrouver le moment fautif.
- L'extraction de frames (`MediaMetadataRetriever`, 2 img/s, `OPTION_CLOSEST`)
  est lente : compter environ 1 à 3 minutes d'analyse pour 10 minutes de vidéo.

## Réglages

Les seuils de vision sont dans `VideoAnalyzer` :

- `STABLE_DIFF` (5) : sensibilité de la détection « scène immobile ».
- `CHANGE_THRESHOLD` (20) : sensibilité du « cette case a changé ».
- `CLOCK_DIFF` (14) : sensibilité de la détection d'appui pendule.
- `FRAME_INTERVAL_US` (500 000) : fréquence d'échantillonnage — un appui
  pendule plus court qu'une demi-seconde peut passer entre deux frames ;
  baisser à 250 000 si les joueurs appuient très vite (blitz).

Si l'app rate des coups → baisser `CHANGE_THRESHOLD` ; si elle voit des coups
fantômes (ombres, reflets) → l'augmenter.
