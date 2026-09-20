# Secure File Service

Service de gestion de fichiers avec scan antivirus obligatoire avant mise à
disposition. Brique d'infrastructure consommée par d'autres systèmes : l'API
est le produit, l'interface web n'est qu'une démonstration du parcours.

## Contraintes de l'énoncé

Java / Spring Boot / React. C'est tout ce qui est imposé.

## Choix que j'ai faits

Ce sont mes décisions, pas des contraintes externes. Je dois pouvoir les
justifier en entretien.

- **Java 21** (LTS, et virtual threads disponibles pour les appels bloquants)
- **Spring Boot 3.3**
- **Maven**
- **MySQL 8** en docker-compose, H2 en test
- **React 18 + TypeScript + Vite**
- **Antivirus : deux implémentations réelles** derrière la même interface,
  ClamAV en TCP INSTREAM et un client HTTP pour une API REST. Le choix se
  fait par configuration. L'énoncé parle d'« un antivirus disponible via
  une API » sans préciser le protocole : couvrir les deux lève l'ambiguïté
  et démontre que l'abstraction tient.

Le stockage et l'antivirus sont derrière des interfaces : changer de base ou
d'antivirus ne touche pas le métier.

## L'invariant du système

Aucun fichier n'est servi sans avoir été scanné ET validé.

Le téléchargement teste `status == CLEAN`, jamais `status != INFECTED`.
Refus par défaut : tout état ajouté plus tard sera refusé automatiquement
plutôt que servi par erreur.

## Architecture

```
POST /api/files
  → stream vers le stockage, checksum SHA-256 calculé au passage
  → persiste en PENDING
  → déclenche un scan asynchrone
  → 202 Accepted { fileId, status }

Scan asynchrone
  → PENDING vers SCANNING
  → lecture en streaming depuis le stockage
  → appel de l'antivirus
  → transition finale

GET /api/files/{id}
  → CLEAN                 : 200 + stream
  → PENDING ou SCANNING   : 409 Conflict (réessayer plus tard)
  → INFECTED              : 403 Forbidden
  → SCAN_FAILED           : 409 Conflict (le scan pourra être relancé)
  → inconnu               : 404

GET /api/files/{id}/status  → 200 { status, dates, tentatives }
GET /api/files              → liste paginée
```

409 sur PENDING et SCAN_FAILED parce que la ressource existe mais n'est pas
encore servable : c'est un conflit d'état, pas une absence ni un refus de droit.

## Machine à états

```
              upload
                ↓
             PENDING
                ↓
            SCANNING ──┬──► CLEAN         terminal, téléchargeable
                       ├──► INFECTED      terminal, jamais servi, conservé
                       └──► SCAN_FAILED   relançable
                                ↑  │
                                └──┘  retry, maximum 3 tentatives
                                      au-delà : SCAN_FAILED définitif
```

Toute autre transition lève une exception. Les règles sont dans le domaine,
pas dans le service.

## Usage du checksum

SHA-256 calculé pendant le transfert, jamais après relecture.

Il sert à vérifier l'intégrité entre écriture et lecture, et à permettre une
déduplication ultérieure : un fichier déjà scanné CLEAN avec le même checksum
n'a pas besoin d'un nouveau scan. La déduplication n'est pas implémentée dans
ce périmètre, c'est une piste d'évolution.

## Décisions à trancher explicitement

Ces points doivent être décidés et documentés, pas laissés aux défauts :

- **Taille maximale acceptée** : `spring.servlet.multipart.max-file-size` et
  `max-request-size`. Choisir une valeur et la justifier.
- **Virtual threads ou pool explicite** : les deux sont défendables. Si
  `spring.threads.virtual.enabled=true`, ne pas configurer en plus un
  `ThreadPoolTaskExecutor` pour `@Async`. Choisir l'un OU l'autre et
  expliquer pourquoi dans le README.
- **Schéma de base** : `ddl-auto` en développement, ou migrations versionnées.
- **Implémentation antivirus active par défaut** : `sfs.antivirus.implementation`
  vaut `clamav`, `http` ou `stub`. Décider laquelle est active au démarrage
  standard et le documenter dans le README.

## Contraintes non négociables

1. **Streaming de bout en bout.** Jamais `getBytes()`, jamais `byte[]` complet,
   jamais `Files.readAllBytes()`. `InputStream` et `StreamingResponseBody`.
2. **Scan asynchrone.** L'upload ne bloque jamais sur le scan.
3. **Stockage et antivirus derrière des interfaces.**
4. **Pas de logique métier dans les controllers.** Ils valident, délèguent, mappent.
5. **Toute ressource fermée.** `try-with-resources` sur chaque `InputStream`,
   `OutputStream`, `Socket`. Aucune fuite, même sur chemin d'erreur.
6. **Découplage par événement.** L'upload publie un `ApplicationEvent`,
   il n'appelle jamais le scan directement. Le listener est annoté
   `@TransactionalEventListener(phase = AFTER_COMMIT)` et `@Async`, et
   vit dans une classe séparée (sinon `@Async` est ignoré).
7. **Agnostique au format.** Le service ne lit jamais le contenu d'un fichier.
   Il conserve `contentType` et `originalFilename` pour les restituer, sans
   les interpréter.

## Conventions de code

- Injection par constructeur, jamais `@Autowired` sur champ
- Champs `final` dans les services
- Records Java pour les DTO, distincts des entités JPA
- Aucune entité exposée par l'API
- Tests : JUnit 5 + Mockito, nommage `methode_devrait_quand`
  exemple : `download_devraitRefuser_quandFichierPending`
- Pas de `System.out.println`, SLF4J uniquement
- Logs et messages d'erreur en anglais

## Structure des packages

```
io.github.alioukara.sfs
├── api          controllers, DTO, GlobalExceptionHandler
├── domain       StoredFile, FileStatus, exceptions métier
├── service      FileService, ScanService
├── storage      FileStorage (interface) + LocalFileStorage
├── antivirus    AntivirusScanner (interface)
│                + ClamAvScanner (TCP INSTREAM)
│                + HttpScanner (API REST)
│                + StubScanner (tests)
└── config       configuration asynchrone, multipart
```

## Ce que tu ne fais pas

- Pas de microservices multiples, pas d'event sourcing, pas de CQRS
- Pas d'authentification (hors périmètre, documenté comme hypothèse)
- Pas de parsing du contenu des fichiers (PDF, docx, etc.)
- Pas de dépendance Maven ajoutée sans validation explicite de ma part
- Pas de configuration implicite : si tu configures quelque chose, les
  valeurs sont explicites et commentées
- Pas de code que je ne peux pas relire en une passe

## Ce que je veux de toi

- **N'écris aucun code tant que je n'ai pas dit GO.** Propose d'abord :
  l'approche, les alternatives, les compromis. J'arbitre, puis tu implémentes.
- **Une tâche à la fois.** Ne prends pas d'avance sur les étapes suivantes.
- Explique les compromis, pas seulement la solution retenue.
- Si une contrainte de ce document rend une approche impossible, dis-le
  plutôt que de la contourner.
- Sois critique. Si un choix que j'ai fait te semble mauvais, dis-le.