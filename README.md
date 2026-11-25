# Boucle

Application Android qui crée une boucle de course ou de randonnée autour de toi, selon une distance et un dénivelé positif visés, sur des chemins qui existent vraiment.

## Fonctionnement

- Départ : position GPS, ou point choisi sur la carte.
- 24 boucles candidates (12 directions, 2 formes) sont préparées autour du départ. Si un D+ est visé, le relief (Open-Meteo) sert à choisir celles qui passent par les bosses ou restent à plat.
- Les meilleures sont tracées sur les chemins OpenStreetMap par [BRouter](https://brouter.de) (profils Sentiers, Mixte ou Tout), puis leur taille est ajustée jusqu'à moins de 2 % d'écart sur la distance.
- Les portions faites deux fois (aller-retour) sont pénalisées. Trois options sont proposées, avec profil altimétrique, repères kilométriques et export GPX.

## Structure

- `app/assets/www/index.html` : toute l'interface et le moteur de boucles (Leaflet).
- `app/src/run/boucle/MainActivity.java` : coquille Android (WebView, position GPS native, enregistrement et partage du GPX).
- `build.sh` : construit `Boucle.apk` sans Gradle (aapt2, javac, d8, zipalign, apksigner).

## Construire

Avec le SDK Android (build-tools 36, plateforme android-36) et le JDK d'Android Studio :

```bash
bash build.sh
```

La clé de signature est créée dans `keystore/` au premier build. Garde-la : sans elle, une nouvelle version ne s'installe pas par-dessus l'ancienne.

## Tester dans un navigateur

```bash
cd app/assets/www && python -m http.server 8767
```

Puis ouvrir http://127.0.0.1:8767/index.html.

## Données

Fonds de carte © OpenStreetMap, OpenTopoMap, Esri. Itinéraires BRouter. Altitudes Open-Meteo.
