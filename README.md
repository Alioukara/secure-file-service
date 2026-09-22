> **Ce document répond aux trois points demandés :**
> [choix techniques et architecturaux](#choix-techniques-et-architecturaux) ·
> [hypothèses formulées](#hypothèses-formulées) ·
> [pistes d'amélioration](#pistes-damélioration)

# Secure File Service

Micro-service de gestion de fichiers avec analyse antivirus obligatoire avant
mise à disposition.

L'API est le produit. L'interface web n'est qu'une démonstration du parcours.

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.3, Maven |
| Persistance | MySQL 8.4, Liquibase, H2 en test |
| Frontend | React 18, TypeScript, Vite, Material UI |
| Antivirus | ClamAV 1.5.4 en TCP INSTREAM, ou une API REST |
| Exécution | Docker Compose, une seule commande |

Les prompts utilisés pendant le développement sont dans
[`ai/prompts.md`](ai/prompts.md).

---

## L'invariant

> Aucun fichier n'est servi sans avoir été scanné **et** validé.

Tout le reste du service découle de cette phrase. Elle ne repose pas sur un test
dans le code, mais sur trois appuis indépendants, parce qu'un invariant qui
tient à une seule ligne meurt le jour où quelqu'un écrit `!=` par distraction.

**1. Le code.** Le téléchargement teste `status == CLEAN`, jamais
`status != INFECTED`. Refus par défaut : tout état ajouté plus tard sera refusé
automatiquement plutôt que servi par erreur.

**2. La topologie.** Deux zones de stockage physiquement distinctes. Tout arrive
en quarantaine ; un fichier ne rejoint la zone servable qu'au passage à `CLEAN`,
par un déplacement atomique. La route de téléchargement ne lit que la zone
servable. Même avec un bug dans le service, il n'y a rien à servir.

**3. Le contrat du scanner.**

> Une implémentation ne rend jamais `CLEAN` sur un contenu qu'elle n'a pas
> analysé en entier. Si elle ne peut pas analyser, elle rend un verdict non
> concluant.

Cette clause est la moins évidente et la plus importante. ClamAV en est la
démonstration : un fichier qui dépasse `MaxFileSize` ou `MaxScanSize` **n'est
pas analysé, et clamd répond quand même `OK`**. Sans garde, le service écrirait
`CLEAN` et distribuerait un fichier jamais scanné, tout en respectant parfaitement
son propre `== CLEAN`. L'invariant serait vrai dans le code et faux dans les
faits.

Deux protections, l'une dans la configuration et l'autre dans le code :
`AlertExceedsMax yes` côté clamd transforme ce silence en verdict, et le scanner
compte lui-même les octets qu'il envoie pour ne pas dépendre d'un fichier de
configuration externe.

---

## Démarrage

```bash
cd docker
docker compose up
```

Une seule commande, rien à installer d'autre que Docker.

> **Le premier démarrage prend plusieurs minutes.** ClamAV charge sa base de
> signatures en mémoire avant de se déclarer prêt, et le backend attend qu'il le
> soit. Tant que le conteneur `clamav` n'est pas `healthy`, rien ne répond sur
> le port 8080. Ce n'est pas un blocage.

Suivre l'avancement :

```bash
docker compose ps          # clamav doit passer de (health: starting) à (healthy)
docker compose logs -f backend
```

Pour tout arrêter :

```bash
docker compose down        # les données survivent, elles sont dans des volumes
docker compose down -v     # tout effacer, base et fichiers stockés compris
```

### L'autre façon : les dépendances par Docker, le code en local

Utile pour ouvrir le projet dans un IDE. Les trois briques se lancent alors
séparément, et rien n'est à configurer : le profil Spring `dev` est actif par
défaut et pointe sur les ports publiés par le compose, le serveur Vite écoute
sur le port 3000, le front appelle `http://localhost:8080` faute de variable
`VITE_API_BASE_URL`, et le backend autorise cette origine.

```bash
cd docker && docker compose up -d mysql clamav   # les dépendances
cd ../backend && mvn spring-boot:run             # l'API, sur 8080
cd ../frontend && npm install && npm run dev     # le front, sur 3000
```

| Service | Port | |
|---|---|---|
| frontend | 3000 | interface de démonstration |
| backend | 8080 | l'API |
| mysql | 3308 | base de données |
| clamav | 3310 | démon antivirus, protocole INSTREAM |
| clamav-rest | 9000 | API REST, profil `http` uniquement |

Une fois les conteneurs sains, l'interface est sur `http://localhost:3000`.

### Basculer d'implémentation d'antivirus

Par défaut, ClamAV en TCP. Pour l'implémentation HTTP :

```bash
cd docker
SFS_ANTIVIRUS_IMPLEMENTATION=http docker compose --profile http up
```

Le profil `http` ajoute le service `clamav-rest` aux quatre autres, il ne les
remplace pas.

La propriété est `sfs.antivirus.implementation`. Elle n'accepte que `clamav` ou
`http` : toute autre valeur fait échouer le démarrage sur un message qui la
nomme, plutôt que sur un « bean manquant » qui ne dirait rien de la faute de
frappe.

L'implémentation HTTP exige deux valeurs **sans défaut**,
`max-scannable-size-bytes` et `max-concurrent-scans`. Elles dépendent de l'API
visée. Une valeur devinée refuserait des fichiers sains, ou pire, rendrait
`CLEAN` sur du contenu tronqué en silence. Absentes, l'application ne démarre
pas. Le `docker-compose.yml` fournit celles de l'image de démonstration.

**Pourquoi le backend attend son antivirus.** Le service est conçu pour
survivre à une panne de scanner : l'échec est classé comme un problème de
transport, le fichier repart en file, et le balayage réessaiera. On pourrait
donc croire qu'attendre est superflu. Ça ne l'est pas, à cause d'un compteur :
`scanAttempts` est borné à trois, avec une minute de recul entre deux essais. Un
scanner injoignable pendant les trois premières minutes brûlerait les trois
tentatives, et des fichiers parfaitement sains finiraient en
`SCAN_FAILED_EXHAUSTED` à cause du démarrage plutôt qu'à cause d'eux. Il
faudrait alors les relancer un par un.

Le backend déclare donc `clamav-rest` en dépendance **facultative**
(`required: false`), sans quoi le profil par défaut, où ce service n'existe pas,
refuserait de démarrer.

Il reste une imperfection assumée : en mode `http`, le backend attend aussi
`clamav`, dont il ne se servira pas. `depends_on` ne sait pas dépendre d'un
profil. La corriger demanderait deux services backend, un par profil, donc de
dupliquer toute leur configuration. Le coût réel est une attente au démarrage,
pas un défaut de correction.

### Viser une vraie API de scan

La commande ci-dessus utilise le conteneur `clamav-rest` fourni pour la
démonstration. Pour interroger une API réelle, il suffit de surcharger les
variables ; le compose garde ces valeurs comme défauts.

```bash
cd docker

SFS_ANTIVIRUS_IMPLEMENTATION=http \
SFS_HTTP_SCANNER_URL=https://antivirus.exemple.com \
SFS_HTTP_SCANNER_SCAN_PATH=/v1/scan \
SFS_HTTP_SCANNER_API_KEY=la-cle \
SFS_HTTP_SCANNER_MAX_SIZE_BYTES=52428800 \
SFS_HTTP_SCANNER_MAX_CONCURRENT_SCANS=4 \
docker compose up
```

| Variable | Rôle | Défaut |
|---|---|---|
| `SFS_HTTP_SCANNER_URL` | la racine de l'API | le conteneur de démonstration |
| `SFS_HTTP_SCANNER_SCAN_PATH` | la route de scan | `/v2/scan` |
| `SFS_HTTP_SCANNER_API_KEY` | la clé, si l'API en demande une | vide, aucun en-tête envoyé |
| `SFS_HTTP_SCANNER_API_KEY_HEADER` | le nom de l'en-tête qui la porte | `X-API-Key` |
| `SFS_HTTP_SCANNER_MAX_SIZE_BYTES` | ce que l'API sait analyser | **obligatoire** |
| `SFS_HTTP_SCANNER_MAX_CONCURRENT_SCANS` | ce qu'elle accepte en parallèle | **obligatoire** |

Le **nom** de l'en-tête d'authentification est configurable, et pas seulement sa
valeur. Les API ne s'accordent pas sur ce point : `X-API-Key: la-cle` chez les
unes, `Authorization: Bearer la-cle` chez les autres. Une seule variable de plus
couvre les deux, sans code supplémentaire.

Le profil `http` n'est alors plus nécessaire, puisqu'il ne sert qu'à démarrer le
conteneur de démonstration.

Une limite à connaître : `depends_on` ne peut rien attendre d'une API distante.
Si elle est injoignable au démarrage, les premiers fichiers déposés consomment
leurs trois tentatives et finissent en `SCAN_FAILED_EXHAUSTED`, à relancer par
`POST /api/files/{id}/rescan`. Voir le paragraphe ci-dessus sur l'attente.

### Vérifier que la chaîne fonctionne

```bash
echo "rapport" > clean.txt
curl -X POST -F "file=@clean.txt" http://localhost:8080/api/files

printf 'X5O!P%%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > eicar.txt
curl -X POST -F "file=@eicar.txt" http://localhost:8080/api/files
```

Les deux répondent `202 Accepted` avec un `fileId` et le statut `PENDING` : le
scan est asynchrone, l'envoi ne l'attend jamais. Suivre l'avancement, puis
tenter le téléchargement :

```bash
curl http://localhost:8080/api/files/{id}/status
curl -i http://localhost:8080/api/files/{id}
```

Le fichier sain répond `200` avec son contenu, l'EICAR répond `403`
définitivement.

Sur ces deux fichiers le verdict tombe en une fraction de seconde, une centaine
de millisecondes mesurée entre `createdAt` et `updatedAt`. Le `409` que
documente la section API existe bien, mais il faut un fichier de plusieurs
dizaines de mégaoctets pour avoir le temps de l'observer :

```bash
head -c 80000000 /dev/urandom > gros.bin
curl -X POST -F "file=@gros.bin" http://localhost:8080/api/files
curl -i http://localhost:8080/api/files/{id}    # 409 + Retry-After
```

C'est le `403` sur l'EICAR qui prouve l'invariant, pas le `409` : le premier
refuse définitivement, le second dit seulement de revenir plus tard. Le bouton
grisé dans l'interface n'est qu'un confort, le refus est porté par l'API et il
tient même si personne n'ouvre le navigateur.

---

## L'API

```
POST /api/files
  413                    la taille dépasse la capacité du scanner actif
  503 + Retry-After      le compte de charge est plein, réessayer plus tard
  503 sans Retry-After   le compte de conservation est plein, intervention requise
  202 Accepted           { fileId, status }

GET /api/files/{id}
  200                    le fichier, en flux, depuis la zone servable
  409 + Retry-After      PENDING, SCANNING, SCAN_FAILED : l'attente a un sens
  409 sans Retry-After   SCAN_FAILED_EXHAUSTED, UNSCANNABLE : l'attente est vaine
  403                    INFECTED
  404                    identifiant inconnu

GET /api/files/{id}/status
  200                    { status, reason, scanAttempts, leaseExpiries, dates }
  404                    identifiant inconnu

POST /api/files/{id}/rescan
  202                    depuis SCAN_FAILED_EXHAUSTED : compteurs remis à zéro
  409                    depuis tout autre état, avec l'état courant dans le corps
  404                    identifiant inconnu

GET /api/files?page=&size=&status=
  200                    { content, page, size, totalElements, totalPages }
```

**`/status` porte la raison, pas seulement l'état.** `UNSCANNABLE` recouvre une
limite dépassée, une archive chiffrée et un contenu illisible ;
`SCAN_FAILED_EXHAUSTED` recouvre plusieurs causes de transport. Un client qui ne
lirait que le statut ne saurait pas quoi faire.

**La pagination a sa propre forme**, et non celle de Spring Data. Exposer `Page`
ferait fuir des champs internes (`pageable`, `sort`, `numberOfElements`) dans le
contrat, et ce format a déjà changé entre versions du framework.

Les erreurs suivent la RFC 7807 (`ProblemDetail`), avec un champ `reason`
exploitable par un client.

**Le `Retry-After` n'est pas décoratif.** Sa présence dit qu'attendre suffira,
son absence dit qu'il faut agir. C'est la seule différence entre deux `409` et
entre deux `503`, et elle porte une information que le code seul ne donne pas.

---

## Choix techniques et architecturaux

### La base est la source de vérité, l'événement n'est qu'un accélérateur

L'upload publie un `ApplicationEvent` consommé par un listener
`@TransactionalEventListener(AFTER_COMMIT)` et `@Async`. C'est propre, mais cet
événement vit en mémoire et meurt avec la JVM : un redéploiement pendant un scan
laisserait le fichier en `SCANNING` pour toujours, sans que le compteur de
tentatives ne le voie.

Un balayage périodique reprend donc les fichiers en attente trop vieux, les
scans dont le bail a expiré, et les échecs mûrs pour une nouvelle tentative,
avec `SELECT ... FOR UPDATE SKIP LOCKED` pour que plusieurs instances se
partagent le travail sans se bloquer.

Ce n'est pas du « scan asynchrone événementiel », c'est de la **reprise sur
incident avec un événement comme accélérateur**.

### La file d'attente, c'est la table

Pas de broker de messages. La table porte l'état, donc elle porte la file. Un
sémaphore en `tryAcquire` sans attente borne la concurrence : plus de permis, on
ne bloque rien, le fichier reste en attente et le balayage le reprendra.

Aucune file en mémoire, aucun thread bloqué, rien à perdre au redémarrage. Et
c'est le mécanisme de reprise qui sert de régulateur, pas un second dispositif.

### Virtual threads, et aucun pool

`spring.threads.virtual.enabled=true`, et **pas de `ThreadPoolTaskExecutor`**.
Un scan est une attente : le thread reste bloqué sur une socket sans rien
calculer. C'est exactement ce que les threads virtuels rendent gratuit, et c'est
ce qui permet d'absorber de nombreux appels simultanés sans dimensionner un pool
au doigt mouillé.

Ajouter un pool par-dessus ajouterait une **seconde borne**, indépendante de la
vraie et forcément mal calée. Elle masquerait celle qui compte, puisqu'un
fichier serait refusé par la taille du pool avant même d'atteindre le sémaphore.
Les threads sont gratuits, les permis ne le sont pas : c'est le sémaphore qui
borne le débit.

Une conséquence est traitée dans le code : un thread virtuel qui bloque à
l'intérieur d'un `synchronized` épingle son porteur, ce qui n'est corrigé qu'en
Java 24. Aucun appel bloquant n'est donc entouré d'un `synchronized`.

### Deux bornes, qui ne règlent pas la même chose

**La borne de débit** est la capacité de l'antivirus, déclarée par
l'implémentation active : le `MaxThreads` de ClamAV et le quota d'une API HTTP
ne sont pas le même nombre.

**La borne d'admission** est le disque de quarantaine, en deux comptes séparés
selon **ce qui les vide** :

| Compte | États | Se vide | Dépassement |
|---|---|---|---|
| Automatique | en attente, en cours, échec relançable | tout seul | `503` + `Retry-After` |
| Sur action | infecté, inanalysable, échec épuisé | jamais seul | `503` sans `Retry-After` |

Un compte unique produirait un `503` de charge déclenché par de l'archivage :
les fichiers infectés sont conservés par décision et ne repartent jamais, ils
finiraient par bloquer tous les envois pour une raison sans rapport avec le
trafic. Les deux situations n'ont pas le même remède.

### La machine à états est une table, pas une prose

Sept états, onze transitions, chacune avec son déclencheur et sa garde. Ce qui
n'est pas dans la table lève une exception. Les règles vivent dans le domaine,
pas dans le service.

La frontière entre un échec **système** et un fichier **inanalysable** n'est pas
« relançable ou définitif », c'est **qui est en cause** :

- un antivirus tombé ne dit rien sur le fichier, et condamner définitivement tout
  ce qui a été envoyé pendant une panne serait faux ;
- un verdict non concluant, lui, porte bien sur le fichier.

Deux compteurs séparés, parce qu'un redéploiement n'est pas la faute du fichier,
et qu'un fichier qui fait tomber le scanner n'est pas une panne
d'infrastructure.

### Le jeton de bail

Un bail expiré ne signifie pas qu'un scan est mort, il peut être simplement
lent. Sans protection, le balayage relancerait un second scan pendant que le
premier tourne, et le verdict tardif du premier s'appliquerait à un fichier dont
l'état a changé.

Chaque passage en cours de scan génère donc un jeton, et toute transition sortant
de cet état est conditionnée à ce jeton. Un verdict qui arrive après la reprise
est jeté.

### Deux implémentations d'antivirus

L'énoncé parle d'« un antivirus disponible via une API » sans préciser le
protocole. Plutôt que de choisir au hasard, la même interface est implémentée
deux fois (ClamAV en TCP INSTREAM, et un client HTTP vers une API REST), le
choix se faisant par configuration.

Une troisième implémentation existe pour les tests. Elle vit dans `src/test` et
n'est **jamais** sur le classpath d'exécution : désactiver l'antivirus ne doit
pas être à portée d'une variable d'environnement mal orthographiée.

Écrire la seconde implémentation a d'ailleurs prouvé l'intérêt de la clause du
contrat. L'image `ajilaag/clamav-rest` tourne avec **`AlertExceedsMax`
désactivé** et ignore son propre `clamd.conf`, dont toutes les directives sont
commentées. Au-delà de 100 Mo, clamd cesse d'analyser et répond quand même
`OK` : le fichier ressortirait `CLEAN` sans avoir été lu en entier. Pour
l'implémentation ClamAV on s'en protège avec `AlertExceedsMax yes` dans notre
propre configuration ; ici c'est hors de portée. Le comptage d'octets côté
client est donc la seule chose qui tienne la clause pour cette implémentation.

C'est exactement ce qui justifie que la clause appartienne à l'interface et non
à une implémentation : une règle qui n'aurait valu que pour ClamAV aurait laissé
passer celle-ci.

### L'interface, et le CORS plutôt qu'un proxy

React 18, TypeScript, Vite et Material UI. Une seule page : dépôt par
glisser-déposer, tableau paginé et filtrable, badge par état, téléchargement
grisé hors `CLEAN`, relance sur les scans épuisés.

Le navigateur appelle l'API **directement**, sans intermédiaire. Le backend
déclare les origines autorisées, jamais `*`, ce qui serait le mauvais réglage
autant que le mauvais signal sur un service de sécurité. `Content-Disposition`
et `Retry-After` sont explicitement exposés, sans quoi le navigateur les
cacherait au JavaScript et le front ne pourrait pas lire le nom du fichier.

Deux points de structure valent d'être signalés.

**Aucun composant ne manipule une erreur HTTP brute.** Un intercepteur la
traduit une fois en un type applicatif, et les hooks déclarent ce type, ce qui
permet d'afficher le `detail` du `ProblemDetail` plutôt qu'un « erreur 409 » qui
jetterait ce que le service a pris soin de dire.

**Le badge d'état est indexé par le type des sept statuts, sans valeur par
défaut.** Un huitième statut ajouté à l'union casse la compilation du front,
exactement comme le `switch` exhaustif casse celle du backend.

### Streaming de bout en bout

Aucun fichier ne transite entièrement en mémoire. Écriture en flux vers le
disque, envoi en trame préfixée vers l'antivirus, `StreamingResponseBody` au
téléchargement.

Une réserve est assumée : `MultipartFile` fait écrire le corps de la requête
dans un fichier temporaire par le conteneur avant que notre code n'y touche. La
contrainte tient donc au sens où rien ne passe sur le tas, pas au sens littéral.
Le véritable bout-en-bout demanderait un envoi en `application/octet-stream` lu
directement, au prix du formulaire web.

### Schéma versionné

Liquibase en changelogs déclaratifs YAML, avec `ddl-auto=validate` en
complément : le changelog fait foi, Hibernate vérifie que les entités
correspondent et refuse de démarrer sinon.

Les identifiants sont stockés en `VARCHAR(36)` et non dans un type natif : H2 et
MySQL n'ont pas le même, et un seul changelog doit valider sur les deux. Les
dates sont en `DATETIME(6)` et non `TIMESTAMP`, qui tronque à la seconde,
s'arrête en 2038 et convertit selon le fuseau de la session.

---

## Réglages couplés : ce qu'on ne peut pas changer isolément

Ces valeurs se tiennent entre elles. Modifier l'une sans l'autre casse quelque
chose, et les quatre premières **échouent en silence**.

| Contrainte | Si elle est violée |
|---|---|
| `AlertExceedsMax yes` dans `clamd.conf` | un fichier trop gros est déclaré propre sans avoir été analysé, puis servi |
| `lease-timeout` > durée du scan le plus long | un scan lent est relancé pendant qu'il tourne, et consomme un second permis |
| `read-timeout-millis` > durée du scan le plus long | un scan lent devient un échec pour rien |
| `StreamMaxLength` > `MaxFileSize` côté clamd | un fichier trop gros est coupé au transport, sans verdict exploitable |
| `max-concurrent-scans` ≤ `MaxThreads` de clamd | le démon est saturé |
| Les deux zones sur le même système de fichiers | le déplacement n'est plus atomique, le service refuse de démarrer, c'est voulu |
| `server.tomcat.max-swallow-size` explicite | le client reçoit une coupure de connexion au lieu du `413` |

Et un dimensionnement : le **quota du compte automatique** vaut grossièrement la
taille maximale acceptée multipliée par le nombre d'envois simultanés que l'on
accepte d'avoir en vol.

Les valeurs de ClamAV ne sont pas supposées, elles ont été lues sur l'image
épinglée :

```bash
docker run --rm clamav/clamav:1.5.4 clamconf \
  | grep -iE 'MaxFileSize|MaxScanSize|StreamMaxLength|AlertExceedsMax|MaxThreads'
```

---

## Hypothèses formulées

L'énoncé laissait plusieurs points ouverts. Voici ce qui a été décidé, et
pourquoi.

**« Un antivirus disponible via une API » ne précise pas le protocole.** Deux
lectures sont possibles : un démon local en TCP, ou un service REST distant.
Les deux sont implémentées derrière la même interface plutôt que d'en choisir
une au hasard.

**Pas d'authentification.** Hors périmètre. Conséquence assumée : aucune limite
par utilisateur, puisqu'il n'y a pas d'identité. Limiter par adresse IP serait
faux derrière un mandataire tout en donnant une fausse impression de protection.

**Le service n'interprète jamais le contenu d'un fichier.** Il conserve le type
et le nom d'origine pour les restituer, sans les lire. Un type absent n'est donc
pas un motif de refus : la norme prévoit une valeur par défaut pour les données
non étiquetées, et l'énoncé parle de « fichiers de natures diverses ».

**Les fichiers infectés sont conservés, et le service ne propose aucun moyen de
les supprimer.** Ils restent en quarantaine avec des permissions restreintes.

Ce choix est volontaire, mais il n'est pas confortable, et il faut en assumer la
conséquence : sans authentification, une route de suppression serait ouverte à
tous. Offrir à n'importe qui l'effacement des traces d'une attaque serait pire
que de ne rien offrir du tout. Un drapeau de configuration ne remplacerait rien,
il ne dit pas *qui* a agi.

La conséquence est réelle : le compte de conservation ne se vide jamais seul, et
un service ayant reçu assez de fichiers infectés finit par refuser tout nouvel
envoi. Le `503` sans `Retry-After` signale exactement ce cas. C'est un blocage
identifié, traité dans les pistes d'amélioration, pas un oubli.

**La taille maximale n'est pas un chiffre arbitraire.** C'est la capacité
d'analyse du scanner actif : *on n'accepte rien qu'on ne peut pas analyser*.
« Tailles très variables » ne veut pas dire illimitées : quatre ordres de
grandeur sont couverts, du kilooctet à la centaine de mégaoctets.

**La vérification des quotas est approximative sous concurrence.** Entre le test
et l'écriture, d'autres envois peuvent aboutir. Une réservation exacte coûterait
un verrou sur le chemin critique, pour une précision dont la borne n'a pas
besoin.

**Le checksum n'est pas un moyen d'éviter un scan.** L'idée « même empreinte,
déjà déclaré propre, donc inutile de rescanner » est fausse en sécurité : le
verdict dépend de la base de signatures, qui change entre deux analyses.

---

## Pistes d'amélioration

**Les fichiers de plusieurs gigaoctets.** Au-delà de la limite du scanner, ce
n'est plus le même problème : il faut un envoi reprenable en plusieurs requêtes
et un scan incrémental. Identifié, non construit.

**Deux voies de scan séparées.** Un gros fichier occupe un permis pendant que
des petits attendent. Le blocage en tête de file est réel, mais il se mesure
avant de se traiter : la durée de scan est journalisée avec la taille, pour
décider sur des chiffres plutôt que sur une intuition.

**Un broker de messages.** La table est une file d'attente pauvre. Elle se
remplacerait par Kafka ou RabbitMQ si le débit l'exigeait, la frontière est
déjà en place, c'est le balayage qui changerait.

**La déduplication du stockage** par empreinte, pour ne pas conserver deux fois
un même contenu. À ne pas confondre avec la déduplication du scan, écartée plus
haut.

**Rétention sur le contenu, permanence sur la trace.** C'est la piste qui lève
le blocage décrit plus haut, et elle vient d'un recadrage : *qu'est-ce que la
preuve ?*

Pour un audit, c'est qu'un fichier nommé X, de telle taille, d'empreinte Y,
déposé à telle date, a été analysé à telle autre et déclaré infecté. Tout cela
est dans la ligne en base, et l'empreinte SHA-256 suffit à rapprocher le dossier
d'une analyse externe si la question se repose. Les octets, eux, sont du
logiciel malveillant vivant conservé sur un disque pour toujours : un risque,
pas un atout.

La ligne resterait donc définitivement, avec son empreinte ; le contenu serait
purgé après un délai. Le compte de conservation se libère,
l'audit survit, et le service cesse d'héberger indéfiniment ce qu'il a refusé.
Le téléchargement continuerait de répondre `403` : l'invariant ne bouge pas.

Coût : un marqueur *contenu purgé*, un balayage de purge, et une entrée de plus
dans les réglages couplés.

**Une route de suppression, le jour où l'authentification existera.** Réservée à
un rôle d'administration et journalisée. C'est la seule forme sous laquelle une
suppression manuelle est défendable sur ce service.

**Le front reste une page unique.** Pas de routage, pas d'état global, pas de
tests. Ce qu'il démontre (polling qui s'arrête tout seul, pagination et filtres
servis par l'API, erreurs affichées telles que le service les formule) suffit
au parcours, mais une interface destinée à des utilisateurs demanderait un
découpage par route et une couverture de tests.

**Métriques et traçabilité.** Durée de scan par tranche de taille, taux
d'occupation des permis, âge du plus vieux fichier en attente. De quoi régler
les valeurs couplées plus haut sur des mesures.

**Authentification et autorisation**, qui feraient apparaître une limite par
principal et une route d'administration pour la relance.

---

## Tests

Lancer la suite en local demande un **JDK 21** et Maven. Le démarrage par
`docker compose`, lui, n'a besoin ni de l'un ni de l'autre : la compilation a
lieu dans l'image.

```bash
cd backend
java -version                     # doit afficher 21
mvn clean test                    # sans Docker
mvn clean test -Dsfs.mysql=true   # avec la base réelle
```

Sur un JDK plus ancien, Maven s'arrête sur `release version 21 not supported`,
un message qui ne dit pas ce qu'il manque.

Les tests qui dépendent d'un vrai MySQL sont désactivés par défaut, pour que la
suite reste exécutable sans Docker.

Trois choix de méthode méritent d'être signalés.

**La table des transitions est écrite deux fois**, dans le code de production
et à la main dans le test. La dériver du code testé produirait un test qui
valide n'importe quel comportement, y compris faux.

**Les requêtes sont vérifiées là où elles tournent.** Liquibase rend le *schéma*
portable, pas les *requêtes* : `SKIP LOCKED` n'existe pas sur H2. La logique de
reprise est donc testée sans base, et les requêtes contre MySQL 8.

**La recherche de signature en flux est éprouvée sur les frontières.** Le
marqueur est cherché à toutes les positions autour des limites de tampon, et
avec des lectures d'un seul octet, parce qu'un motif à cheval sur deux lectures
est le cas que ce genre de code rate.

Le front n'a pas de tests. C'est assumé et signalé dans les pistes : l'effort a
porté sur l'API, qui est le produit.

---

## Structure

```
backend/     API Spring Boot
  domain/       StoredFile, FileStatus, la machine à états
  service/      orchestration, scan, balayage de reprise
  storage/      les deux zones, le déplacement atomique
  antivirus/    l'interface et ses implémentations
  repository/   accès aux données, requêtes de reprise
  api/          controllers, DTO, gestion centralisée des erreurs
  config/       sémaphore de scan, CORS, garde sur le choix d'antivirus

frontend/    interface de démonstration
  types/        contrats partagés avec l'API
  constants/    statuts servables, groupes de filtres, cadence du polling
  services/     client HTTP et intercepteur
  handlers/     traduction des erreurs
  hooks/        accès aux données
  components/   présentation, découpée par domaine
  theme/        palette et jetons, mode clair et sombre
  utils/        formatage des tailles et des dates

docker/      docker-compose, Dockerfiles, configuration ClamAV
ai/          prompts utilisés pendant le développement
```
