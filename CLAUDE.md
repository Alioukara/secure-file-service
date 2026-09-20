# Secure File Service

Service de gestion de fichiers avec scan antivirus obligatoire avant mise à
disposition. Brique d'infrastructure consommée par d'autres systèmes : l'API
est le produit, l'interface web n'est qu'une démonstration du parcours.

## Contraintes de l'énoncé

Java / Spring Boot / React. C'est tout ce qui est imposé.

## Choix que j'ai faits

Ce sont mes décisions, pas des contraintes externes. Chacune doit pouvoir être
justifiée.

- **Java 21** (LTS, et virtual threads pour les appels bloquants)
- **Spring Boot 3.3**
- **Maven**
- **MySQL 8** en docker-compose, H2 en test
- **Liquibase**, changelogs déclaratifs en YAML, avec `ddl-auto=validate` en
  complément. Du SQL brut dans un changeset ferait perdre la traduction de
  dialecte, donc la portabilité H2 / MySQL.
- **React 18 + TypeScript + Vite**
- **Antivirus : deux implémentations réelles** derrière la même interface,
  ClamAV en TCP INSTREAM et un client HTTP pour une API REST. Le choix se
  fait par configuration. L'énoncé parle d'« un antivirus disponible via
  une API » sans préciser le protocole : couvrir les deux lève l'ambiguïté
  et démontre que l'abstraction tient. Les deux doivent avoir le même niveau
  de finition, tests et gestion d'erreur compris.
- **Image ClamAV épinglée** : `clamav/clamav:1.5.4`. Pas `:stable`, qui
  bougerait d'une exécution à l'autre. Pas `_base`, qui arrive sans base de
  signatures et impose un `freshclam` au démarrage.

Le stockage et l'antivirus sont derrière des interfaces : changer de base ou
d'antivirus ne touche pas le métier.

## L'invariant du système

Aucun fichier n'est servi sans avoir été scanné ET validé.

Le téléchargement teste `status == CLEAN`, jamais `status != INFECTED`.
Refus par défaut : tout état ajouté plus tard sera refusé automatiquement
plutôt que servi par erreur.

Cet invariant repose sur trois appuis, et les trois sont obligatoires.

1. **Le code** : `== CLEAN`, refus par défaut.
2. **La topologie** : deux zones de stockage physiquement distinctes. La route
   de téléchargement ne lit que la zone servable, dans laquelle un fichier
   n'entre qu'au passage à CLEAN.
3. **Le contrat du scanner** :

   > Une implémentation ne rend jamais CLEAN sur un contenu qu'elle n'a pas
   > analysé en entier. Si elle ne peut pas analyser, elle rend un verdict non
   > concluant.

   Sans cette clause, l'invariant se retourne en silence : un fichier qui
   dépasse `MaxFileSize` ou `MaxScanSize` n'est pas analysé et clamd répond
   malgré tout **OK**. Chaque implémentation honore la clause à sa façon —
   `AlertExceedsMax TRUE` dans `clamd.conf` pour ClamAV, une vérification de
   taille ou une lecture fine de la réponse pour le client HTTP. Le stub doit
   pouvoir simuler ce verdict, sinon la règle n'est pas testable.

## Architecture

### Deux zones de stockage

```
zone de quarantaine            zone servable
  permissions restreintes        lue par le téléchargement
  tout ce qui arrive             rien d'autre que du CLEAN
  INFECTED et UNSCANNABLE
  y restent définitivement
        │
        └──── move atomique, uniquement au passage à CLEAN ────►
```

Le `move` doit être sur la même partition, sinon ce n'est plus atomique mais
une copie.

**Ordre retenu : move d'abord, commit ensuite.**

**Règle de réconciliation, obligatoire**, à appliquer au démarrage de chaque
scan : si l'objet est absent de la quarantaine et présent en zone servable,
c'est qu'un scan précédent a conclu CLEAN sans pouvoir commiter. On termine la
transition vers `CLEAN` au lieu de rescanner.

La quarantaine a des permissions restreintes : les `INFECTED` et les
`UNSCANNABLE` y sont conservés définitivement, un dossier lisible par les
voisins du volume déplacerait le problème au lieu de le résoudre.

### Flux

```
POST /api/files
  → 413 si la taille dépasse la capacité déclarée du scanner actif
  → 503 + Retry-After   si le compte AUTOMATIQUE dépasse son seuil (charge)
  → 503 sans Retry-After si le compte SUR ACTION dépasse le sien (intervention)
  → stream vers la QUARANTAINE, checksum SHA-256 calculé au passage
  → persiste en PENDING
  → publie un événement (accélérateur, pas source de vérité)
  → 202 Accepted { fileId, status }

Scan
  → tryAcquire sur le sémaphore ; si plein, on ne fait rien et le fichier
    reste PENDING — le balayage le reprendra
  → PENDING vers SCANNING : scanStartedAt = now, nouveau jeton de bail
  → réconciliation : si absent de la quarantaine et présent en zone
    servable, conclure CLEAN sans rescanner
  → lecture en streaming depuis la quarantaine
  → appel de l'antivirus
  → si CLEAN : move vers la zone servable, puis transition
  → sinon : transition, le fichier ne bouge pas
  → toute transition sortant de SCANNING est conditionnée au jeton de bail

Balayage périodique — la file, c'est la table
  → reprend les PENDING trop vieux
  → reprend les SCANNING dont le bail a expiré
  → reprend les SCAN_FAILED après backoff
  → SELECT ... FOR UPDATE SKIP LOCKED

GET /api/files/{id}
  → CLEAN                              : 200 + stream depuis la zone servable
  → PENDING, SCANNING, SCAN_FAILED     : 409 + Retry-After
  → SCAN_FAILED_EXHAUSTED, UNSCANNABLE : 409 sans Retry-After
  → INFECTED                           : 403 Forbidden
  → inconnu                            : 404

POST /api/files/{id}/rescan
  → SCAN_FAILED_EXHAUSTED : 202, compteurs remis à zéro, retour en PENDING
  → tout autre état       : 409
  → inconnu               : 404

GET /api/files/{id}/status
  → 200 { status, raison, dates, scanAttempts, leaseExpiries }
GET /api/files              → liste paginée
```

409 sur tout ce qui n'est pas servable : la ressource existe mais son état
l'empêche d'être rendue. `Retry-After` uniquement quand réessayer seul a un
sens.

Deux 413 à traiter dans le `GlobalExceptionHandler`, qui ne sont pas levés au
même endroit : celui du conteneur sur le plafond d'infrastructure
(`MaxUploadSizeExceededException`, avant le controller) et celui du service sur
la capacité du scanner actif.

Les deux 503 doivent être distinguables dans le corps de la réponse : l'un veut
dire « attends », l'autre « appelle quelqu'un ».

### Borne sous charge

Deux bornes, qui ne règlent pas la même chose.

**La borne de débit : la capacité de l'antivirus.** Un sémaphore la
matérialise, en `tryAcquire` sans attente : plus de permis, on ne bloque rien,
le fichier reste PENDING et le balayage le reprendra. Pas de
`ThreadPoolTaskExecutor` — il n'ajouterait qu'une seconde borne, mal calée, qui
masquerait celle-ci.

Le nombre de permis est **déclaré par le scanner**, comme sa taille maximale
analysable. Figer cette valeur dans la configuration du service remettrait une
donnée d'implémentation dans le métier.

**La borne d'admission : le disque de quarantaine, en deux comptes.** Le
critère n'est pas « terminal ou pas » mais ce qui vide le compte :

| Compte | États | Se vide | Dépassement |
|---|---|---|---|
| Automatique | `PENDING`, `SCANNING`, `SCAN_FAILED` | tout seul | 503 + `Retry-After` |
| Sur action | `INFECTED`, `UNSCANNABLE`, `SCAN_FAILED_EXHAUSTED` | jamais seul | 503 sans `Retry-After`, message distinct |

Les deux sommes se calculent depuis la base (`SUM(size)` groupé par état), pas
depuis le système de fichiers.

Rien par utilisateur dans ce périmètre : sans authentification il n'y a pas
d'identité, et limiter par IP serait faux derrière un proxy.

## Machine à états

Ce qui n'est pas dans les tables ci-dessous est interdit, et toute tentative
lève une exception. Les règles sont dans le domaine, pas dans le service.

### Les sept états

| État | Cause | Sortie |
|---|---|---|
| `PENDING` | en file d'attente | oui |
| `SCANNING` | scan en cours | oui |
| `SCAN_FAILED` | **le système** a échoué, reprise automatique armée | oui, balayage |
| `SCAN_FAILED_EXHAUSTED` | **le système** a échoué, reprise automatique arrêtée | oui, relance explicite |
| `CLEAN` | — | terminal |
| `INFECTED` | — | terminal |
| `UNSCANNABLE` | **le fichier** est inanalysable | terminal |

La frontière entre `SCAN_FAILED*` et `UNSCANNABLE` n'est pas « relançable ou
définitif », c'est **qui est en cause**. `SCAN_FAILED_EXHAUSTED` n'est pas
terminal : seule la reprise *automatique* s'arrête.

### Les onze transitions

| # | De | Vers | Déclencheur | Garde | Effet |
|---|---|---|---|---|---|
| T1 | — | `PENDING` | upload commité | — | écrit en quarantaine |
| T2 | `PENDING` | `SCANNING` | événement ou balayage | permis acquis | `scanStartedAt = now`, nouveau jeton de bail |
| T3 | `SCANNING` | `CLEAN` | verdict propre | jeton valide | move vers zone servable |
| T4 | `SCANNING` | `INFECTED` | signature détectée | jeton valide | ne bouge pas |
| T5 | `SCANNING` | `SCAN_FAILED` | erreur de transport | jeton valide, `scanAttempts + 1 < 3` | `scanAttempts++` |
| T6 | `SCANNING` | `SCAN_FAILED_EXHAUSTED` | erreur de transport | jeton valide, `scanAttempts + 1 >= 3` | `scanAttempts++` |
| T7 | `SCANNING` | `UNSCANNABLE` | verdict non concluant | jeton valide | définitif dès la 1ʳᵉ fois |
| T8 | `SCAN_FAILED` | `PENDING` | balayage, après backoff | — | remise en file |
| T9 | `SCANNING` | `PENDING` | bail expiré | `leaseExpiries + 1 < 2` | `leaseExpiries++` |
| T10 | `SCANNING` | `SCAN_FAILED_EXHAUSTED` | bail expiré | `leaseExpiries + 1 >= 2` | `leaseExpiries++` |
| T11 | `SCAN_FAILED_EXHAUSTED` | `PENDING` | `POST /rescan` | — | compteurs remis à zéro |

### Le jeton de bail

Un bail expiré ne signifie pas qu'un scan est mort — il peut être simplement
lent. Sans jeton, le balayage relance un second scan sur le même fichier
pendant que le premier tourne encore, et le verdict tardif s'applique sur un
état qui a changé.

Chaque passage en `SCANNING` génère donc un jeton. Toute transition sortant de
`SCANNING` s'écrit `UPDATE ... WHERE id = ? AND status = 'SCANNING' AND
lease_token = ?` — zéro ligne touchée, le verdict tardif est jeté.

T9 ne libère aucun permis : le balayage ne le détient pas.

### Les deux compteurs

| Compteur | Incrémenté par | Borne | Remis à zéro par |
|---|---|---|---|
| `scanAttempts` | T5, T6 — l'antivirus a répondu par un échec | 3 | T11 |
| `leaseExpiries` | T9, T10 — aucun verdict n'a été rendu | 2 | T11 |

Séparés parce qu'un redéploiement n'est pas la faute du fichier, et qu'un
fichier qui fait tomber le scanner n'est pas une panne d'infrastructure. Les
deux comptent des **événements**, pas des succès : la nième occurrence est
celle qui épuise.

### Classer un échec

- **Erreur de transport** → T5 / T6. Timeout, socket fermée, antivirus
  injoignable. Le système est en cause.
- **Verdict non concluant** → T7. `Heuristics.Limits.Exceeded.*`, archive
  chiffrée, contenu illisible, dépassement de `StreamMaxLength`. Le fichier est
  en cause, définitif dès la première fois.

Un verdict non concluant ne se mappe **pas** sur `INFECTED`.

## Usage du checksum

SHA-256 calculé pendant le transfert, jamais après relecture.

**Il ne sert pas à éviter un scan.** Le verdict dépend de la base de
signatures, qui change entre deux scans : un fichier déclaré propre hier peut
être détecté aujourd'hui. La déduplication du *stockage* reste une piste, celle
du *scan* est à écarter.

**Où il est vérifié reste à trancher.** Tant que ce n'est pas tranché, ne pas
prétendre qu'il garantit l'intégrité : il est calculé et conservé, c'est tout.

## Décisions tranchées

- **Taille maximale : deux plafonds, pas un.** Une limite unique attachée à un
  antivirus précis ferait fuir l'abstraction.

  1. **Plafond d'infrastructure**, statique : `max-file-size` et
     `max-request-size`. Indépendant de l'antivirus, au moins égal à la plus
     grande limite des implémentations supportées.
  2. **Plafond fonctionnel**, dynamique : la capacité déclarée par le scanner
     actif, exposée par `AntivirusScanner`.

- **Implémentation active par défaut : `clamav`.** `sfs.antivirus.implementation`
  vaut `clamav` ou `http`, rien d'autre. Le stub vit dans `src/test` et n'est
  câblé que par le contexte de test : une variable d'environnement ne doit pas
  pouvoir désactiver l'antivirus en silence.

- **Virtual threads**, `spring.threads.virtual.enabled=true`, sans
  `ThreadPoolTaskExecutor`. La limite n'est pas le thread, c'est l'antivirus.

- **Schéma de base : Liquibase**, changelogs YAML déclaratifs, plus
  `ddl-auto=validate`.

- **Les chiffres**, chacun déduit du précédent :

  | Réglage | Valeur | D'où elle vient |
  |---|---|---|
  | capacité déclarée par `ClamAvScanner` | 100 Mo | le `MaxFileSize` de l'image épinglée |
  | `spring.servlet.multipart.max-file-size` | 100 Mo | au moins la plus grande limite des implémentations |
  | `max-request-size` | 105 Mo | overhead multipart |
  | permis déclarés par `ClamAvScanner` | 8 | `MaxThreads` vaut 12, on garde de la marge |
  | quota du compte automatique | 2 Go | 100 Mo × 20 uploads simultanés en vol |
  | quota du compte sur action | 5 Go | conservation des INFECTED et UNSCANNABLE |
  | `server.tomcat.max-swallow-size` | 10 Mo | arbitrage entre 413 propre et connection reset |

  **Réserve** : les 100 Mo et les 8 permis viennent des défauts de ClamAV
  récent, pas de l'image épinglée. Les lire avant de figer :
  `docker run --rm clamav/clamav:1.5.4 clamconf | grep -iE 'MaxFileSize|MaxScanSize|StreamMaxLength|AlertExceedsMax'`

  `AlertExceedsMax TRUE` est obligatoire dans `clamd.conf`.

### Reste à trancher

- **Les limites du `HttpScanner`** — taille maximale analysable et scans
  concurrents. Elles dépendent de l'API visée et n'ont pas de défaut
  raisonnable.
- **`GET /api/files`** — la liste paginée montre-t-elle les `INFECTED` ?
- **Le sort de la contrainte n°8.** Elle justifie le `ReentrantLock` par la
  sérialisation de l'accès au socket ClamAV. Or avec 8 permis il y a 8
  connexions INSTREAM distinctes, donc rien à sérialiser. Le piège
  `synchronized` / thread virtuel reste vrai ; sa justification est à réécrire.
- **Le livrable** : périmètre du front, stratégie de test, contenu du README,
  services du docker-compose.

### Se tranchent en écrivant le code, pas ici

- où le checksum est effectivement vérifié
- `SELECT ... FOR UPDATE SKIP LOCKED` sur H2 en test : Liquibase règle la
  portabilité du schéma, pas celle des requêtes. À vérifier tôt, la réponse
  peut imposer Testcontainers pour les tests de reprise.
- la correspondance exacte entre les codes d'erreur de chaque antivirus et les
  transitions T5 / T6 / T7

### Assumé, pas à trancher

Avec `MultipartFile`, Spring écrit d'abord le corps dans un fichier temporaire
Tomcat, puis le service le streame vers la quarantaine : deux écritures disque.
La contrainte « streaming de bout en bout » tient au sens où rien ne passe en
`byte[]` sur le tas, pas au sens littéral. Le vrai bout-en-bout demanderait un
`POST` en `application/octet-stream`, ce qui casse le formulaire React. Choix
assumé et documenté.

## Contraintes non négociables

1. **Streaming de bout en bout.** Jamais `getBytes()`, jamais `byte[]` complet,
   jamais `Files.readAllBytes()`. `InputStream` et `StreamingResponseBody`.
   La seule réserve est le fichier temporaire du conteneur, voir « Assumé ».
2. **Scan asynchrone.** L'upload ne bloque jamais sur le scan.
3. **Stockage et antivirus derrière des interfaces.** Aucune donnée
   d'implémentation dans le métier : `AntivirusScanner` déclare lui-même sa
   taille maximale analysable et son nombre de scans concurrents.
4. **Pas de logique métier dans les controllers.** Ils valident, délèguent, mappent.
5. **Toute ressource fermée.** `try-with-resources` sur chaque `InputStream`,
   `OutputStream`, `Socket`. Aucune fuite, même sur chemin d'erreur.
6. **La base est la source de vérité, l'événement n'est qu'un accélérateur.**
   L'upload publie un `ApplicationEvent`, il n'appelle jamais le scan
   directement. Le listener est annoté `@TransactionalEventListener(phase =
   AFTER_COMMIT)` et `@Async`, et vit dans une classe séparée (sinon `@Async`
   est ignoré). Cet événement meurt avec la JVM : le balayage périodique est
   donc obligatoire, pas optionnel.

   Ce n'est pas du « scan asynchrone événementiel », c'est de la **reprise sur
   incident avec un événement comme accélérateur**.
7. **Agnostique au format.** Le service ne lit jamais le contenu d'un fichier.
   Il conserve `contentType` et `originalFilename` pour les restituer, sans
   les interpréter.
8. **Jamais `synchronized` autour d'un appel bloquant.** Un thread virtuel qui
   bloque dans un `synchronized` épingle son carrier thread (corrigé seulement
   en Java 24). `ReentrantLock` si un verrou est nécessaire.
9. **Un fichier n'entre dans la zone servable qu'au passage à CLEAN.** Le
   téléchargement ne lit aucune autre zone.
10. **Jamais CLEAN sur un contenu non analysé en entier.** Une règle qui ne
    vaudrait que pour une implémentation n'a rien à faire ici : toute décision
    de ce document doit tenir pour les trois.

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
├── service      FileService, ScanService, ScanRecoveryJob (balayage)
├── storage      FileStorage (interface) + LocalFileStorage
│                deux zones : quarantaine et zone servable
├── antivirus    AntivirusScanner (interface)
│                + ClamAvScanner (TCP INSTREAM) — actif par défaut
│                + HttpScanner (API REST)
└── config       configuration asynchrone, multipart, sémaphore de scan

src/test
└── antivirus    StubScanner — jamais sur le classpath d'exécution
```

## Ce que tu ne fais pas

- Pas de microservices multiples, pas d'event sourcing, pas de CQRS
- Pas d'authentification (hors périmètre, documenté comme hypothèse)
- Pas de parsing du contenu des fichiers (PDF, docx, etc.)
- **Pas de broker de messages** (Kafka, RabbitMQ, Redis) pour la file de scan.
  Le balayage périodique répond au même besoin pour quelques dizaines de
  lignes. Une phrase de README suffit : la table est une file pauvre, elle se
  remplacerait par un broker si le débit l'exigeait.
- **Pas de circuit breaker** (Resilience4j). Un timeout explicite plus
  SCAN_FAILED couvre le besoin.
- **Pas de seconde implémentation de stockage** (S3, MinIO). L'interface suffit
  à démontrer le découplage.
- **Pas de SSE ni de WebSocket** pour notifier la fin du scan. `/status` existe,
  le polling suffit et c'est cohérent avec le 202.
- **Pas de deux voies de scan séparées** petits/gros fichiers. Le head-of-line
  blocking est réel mais il se mesure d'abord : logger la durée de scan avec la
  taille, citer les deux voies comme suite logique dans le README.
- Pas de chiffrement au repos, pas de rate limiting, pas de métriques
  Micrometer complètes, pas de tests de charge Gatling.
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
