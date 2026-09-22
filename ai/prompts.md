# Les prompts utilisés

Outil : Claude Code.

Le contexte permanent vit dans `CLAUDE.md` à la racine : l'invariant, la machine
à états, les contraintes non négociables et les conventions de code. Il est relu
au début de chaque session, ce qui évite de redonner le cadre à chaque prompt.

Les prompts sont classés par sujet et donnés dans l'ordre où ils ont été
envoyés. Les décisions qui en sont sorties sont documentées dans le README,
section « Choix techniques et architecturaux ».

---

## Cadrage, avant toute ligne de code

```
Lis CLAUDE.md.

Avant toute ligne de code, je veux que tu challenges l'architecture
que j'ai posée.

Le service reçoit des fichiers, les fait scanner par un antivirus
externe (lent, faillible), et ne les sert que si le scan est propre.
Contrainte : nombreux utilisateurs simultanés, fichiers de tailles
très variables.

Donne-moi :
1. Les 3 risques principaux de cette architecture
2. Pour chacun, ce que tu ferais différemment et à quel coût
3. Ce qui serait de la sur-ingénierie pour un test technique destiné
   à être discuté en entretien, et qu'il ne faut PAS faire

Ne code rien.
```

```
Trois décisions sont marquées "à trancher" dans CLAUDE.md :

1. Taille maximale acceptée
2. Virtual threads OU ThreadPoolTaskExecutor pour le scan asynchrone
3. ddl-auto ou migrations versionnées

Pour chacune, donne-moi les options avec leurs compromis. Je choisis.
Ne code rien.
```

```
Est-ce que cette décision sur la taille maximale couvre bien les
trois implémentations d'antivirus, ou est-ce qu'elle n'en arrange
qu'une ?
```

```
Fais la table des transitions d'état, propose les chiffres associés,
et pars du principe que l'antivirus actif par défaut est ClamAV.

Je veux des tables, pas de la prose : en prose les règles se
contredisent sans qu'on le voie.
```

---

## Le socle

```
GO.

Génère le pom.xml pour Spring Boot 3.3 / Java 21 avec :
spring-boot-starter-web, spring-boot-starter-data-jpa,
spring-boot-starter-validation, mysql-connector-j (runtime),
h2 (test), spring-boot-starter-test.

Pas d'autre dépendance. Si tu penses qu'il en manque une, propose-la
et justifie avant de l'ajouter.

Donne aussi l'arborescence des packages selon CLAUDE.md et la classe
Application.

Le backend va dans backend/ et le front dans frontend/, pas de
backend à la racine : le dépôt doit se lire comme un projet full
stack dès l'arborescence.
```

---

## Le domaine et la machine à états

```
GO pour le domaine.

1. L'enum FileStatus avec canTransitionTo() et isDownloadable().
   isDownloadable() doit retourner status == CLEAN, jamais != INFECTED.

2. L'entité StoredFile avec une fabrique statique pending(),
   une méthode privée transitionTo(), et des méthodes publiques
   métier : startScanning(), markClean(), markInfected(),
   markScanFailed(). Pas de setters. Verrouillage optimiste.

3. Les exceptions métier.

Puis les tests JUnit 5 : toutes les transitions autorisées passent,
les interdites lèvent, isDownloadable() vrai uniquement pour CLEAN.
```

```
Est-ce qu'on peut avoir deux SCAN_FAILED, un terminal et l'autre non ?

Je ne veux pas qu'un fichier reste bloqué en réessai infini, mais je
ne veux pas non plus condamner définitivement un fichier à cause
d'une panne d'infrastructure. Dis-moi si la distinction tient, et ce
qu'elle change dans la table des transitions.
```

---

## Le stockage en deux zones

```
GO pour le stockage.

Interface FileStorage, avec la notion de deux zones :
- quarantaine : reçoit tout ce qui arrive, permissions restreintes,
  les INFECTED et UNSCANNABLE y restent définitivement
- servable : rien d'autre que du CLEAN, seule zone lue au téléchargement

Méthodes :
- store(key, InputStream, sizeHint) - écrit TOUJOURS en quarantaine
- retrieve(zone, key)
- promote(key) - move atomique quarantaine vers servable, uniquement
  au passage à CLEAN
- exists(zone, key) - indispensable à la règle de réconciliation

Contrainte de conception : aucune méthode ne doit permettre d'écrire
directement dans la zone servable. promote() est le seul chemin.
C'est ce qui rend l'invariant topologique au lieu de conditionnel.

Pas de delete() sauf si tu le justifies : rien dans le flux ne
supprime, les états terminaux sont conservés.

Puis LocalFileStorage :
- écriture en streaming, jamais de byte[], try-with-resources partout
  y compris sur chemin d'erreur
- répertoire de base configurable, valeurs explicites et commentées
- les deux zones sur la MÊME partition, sinon le move n'est pas atomique
- permissions restreintes sur la quarantaine, posées à la création
- clés éclatées en sous-répertoires (ab/cd/uuid)
- garde qui empêche une clé de sortir du répertoire de base

Tests : la garde anti-traversée, promote() qui déplace vraiment,
exists() sur les deux zones, et le fait qu'aucun chemin n'écrit en
zone servable hors promote().
```

---

## L'antivirus : le contrat avant les implémentations

```
GO pour l'antivirus.

Interface AntivirusScanner :
- scan(InputStream) -> ScanResult
- maxScannableSize() - la taille max que CETTE implémentation sait
  analyser
- maxConcurrentScans() - sa capacité, pas un réglage du service

Ces deux limites sont déclarées par le scanner et jamais figées dans
la config du service : ClamAV et une API HTTP n'ont pas les mêmes.

ScanResult doit distinguer trois verdicts, parce qu'ils mènent à trois
transitions différentes :
- CLEAN        -> T3
- INFECTED     -> T4, avec la signature
- INCONCLUSIVE -> T7 (UNSCANNABLE), avec la raison : limite dépassée,
                  archive chiffrée, contenu illisible

Une erreur de transport (timeout, socket fermée, scanner injoignable)
n'est PAS un verdict : c'est une exception, elle mène à T5/T6.

Contrat non négociable : une implémentation ne rend jamais CLEAN sur
un contenu qu'elle n'a pas analysé en entier. Si elle ne peut pas
analyser, elle rend INCONCLUSIVE.

Puis StubScanner, dans src/test, jamais sur le classpath d'exécution :
détecte la signature EICAR EN STREAMING, avec une recherche glissante
qui fonctionne même si le marqueur chevauche deux buffers. Il doit
aussi pouvoir simuler un verdict INCONCLUSIVE, sinon le contrat n'est
pas testable.

Pas de synchronized autour d'un appel bloquant : ReentrantLock si un
verrou est nécessaire.

Écris le test qui prouve que la recherche glissante marche, avec le
marqueur coupé en deux entre deux lectures.
```

---

## L'upload et les bornes sous charge

```
GO pour l'upload.

D'abord le changelog Liquibase de la table stored_file, sinon
ddl-auto=validate refusera de démarrer. Format YAML déclaratif, pas de
SQL brut.

Puis le repository Spring Data.

Puis POST /api/files, multipart, dans cet ordre :
1. 413 si la taille dépasse maxScannableSize() du scanner actif
2. 503 + Retry-After si le compte AUTOMATIQUE dépasse son seuil
   (PENDING + SCANNING + SCAN_FAILED), c'est de la charge
3. 503 SANS Retry-After si le compte SUR ACTION dépasse le sien
   (INFECTED + UNSCANNABLE + SCAN_FAILED_EXHAUSTED), ça demande une
   intervention
4. stream vers la QUARANTAINE, jamais de byte[] complet
5. checksum SHA-256 calculé PENDANT le transfert (DigestInputStream)
6. persistance en PENDING
7. publication d'un ApplicationEvent, jamais d'appel direct au scan
8. 202 Accepted { fileId, status }

Les deux comptes se calculent en base, SUM(size_bytes) groupé par
status, jamais depuis le système de fichiers.

Les deux 503 doivent être distinguables dans le corps de la réponse :
l'un veut dire « attends », l'autre « appelle quelqu'un ».

GlobalExceptionHandler : deux sources de 413 à traiter, celle du
conteneur (MaxUploadSizeExceededException, levée avant le controller)
et celle du service sur la capacité du scanner.

Pas de logique métier dans le controller. Records pour les DTO, aucune
entité exposée.

Donne aussi application.yml, valeurs explicites et commentées.
```

---

## Le scan et la reprise sur incident

```
GO pour le scan asynchrone.

Point d'attention : je ne veux PAS d'une transaction ouverte pendant
toute la durée du scan. Découpe en trois :
1. transaction courte pour passer en SCANNING
2. le scan HORS transaction
3. transaction courte pour écrire le résultat

Pour le déclenchement, je veux l'approche événementielle :
l'upload publie un ApplicationEvent, un listener annoté
@TransactionalEventListener(phase = AFTER_COMMIT) et @Async le
consomme. Deux raisons : l'événement n'est délivré qu'une fois la
transaction committée, donc le worker trouve toujours la ligne ;
et si la transaction rollback, aucun scan n'est déclenché.

Le listener doit être dans une classe séparée, sinon @Async est
ignoré (auto-invocation, la méthode ne passe pas par le proxy).

Pas de TransactionSynchronizationManager : c'est l'API bas niveau,
plus verbeuse et plus couplée.
```

```
GO pour le balayage périodique.

Sans lui, trois cas restent bloqués pour toujours :
- un PENDING qui n'a trouvé aucun permis libre au moment de l'événement
- un SCANNING dont le worker est mort, redéploiement compris
- un SCAN_FAILED qui attend sa reprise après backoff

L'événement meurt avec la JVM. Le balayage n'est donc pas une
optimisation, c'est la garantie ; l'événement n'est que
l'accélérateur.

SELECT ... FOR UPDATE SKIP LOCKED pour que plusieurs instances se
partagent le travail sans se bloquer. Dis-moi tout de suite si H2 ne
le supporte pas en test, ça changerait la stratégie de test.

Récupérer d'abord (baux expirés, SCAN_FAILED mûrs), distribuer
ensuite. Une transaction par fichier, jamais une pour tout le lot.
```

---

## Le téléchargement et les erreurs

```
GO pour le téléchargement.

GET /api/files/{id} :
- CLEAN : 200 + StreamingResponseBody
- PENDING/SCANNING/SCAN_FAILED : 409
- INFECTED : 403
- inconnu : 404

La règle de refus doit exiger l'unique cas autorisé, pas énumérer
les cas interdits. Utilise un switch exhaustif sur l'enum pour que
le compilateur refuse de compiler si un état est ajouté sans être
traité.

Puis le @RestControllerAdvice, sans jamais exposer de stack trace.
```

```
GO pour les tests du controller.

@WebMvcTest avec le service mocké.
Un @ParameterizedTest qui couvre d'un coup tous les états non
téléchargeables.
Plus : l'upload répond 202 et pas 200.
```

---

## ClamAV, en INSTREAM sur TCP

```
GO pour ClamAV.

ClamAvScannerImpl, protocole INSTREAM sur TCP.

PROTOCOLE
zINSTREAM\0, puis des chunks préfixés de leur taille en big-endian
sur 4 octets, puis un zéro final. Lecture de la réponse ensuite.
Socket et flux en try-with-resources, fermés même sur chemin d'erreur.

Hôte, port et timeouts en configuration, valeurs explicites et
commentées.

ARRÊT ANTICIPÉ
ClamAV répond dès qu'il détecte une signature, sans attendre la fin du
transfert, puis ferme. Deux choses à gérer, pas une :
- attraper l'IOException sur l'écriture et aller lire la réponse, qui
  est déjà là. C'est ça qui protège vraiment du broken pipe.
- in.available() entre les chunks pour court-circuiter plus tôt. C'est
  une optimisation, pas une garantie : ne repose pas dessus seul.

RÉPONSES VERS VERDICTS
- "stream: OK"                        -> ScanResult.clean()
- "stream: <signature> FOUND"         -> ScanResult.infected(signature)
- "Heuristics.Limits.Exceeded.*"      -> ScanResult.inconclusive(raison)
- "... ERROR", INSTREAM size limit    -> ScanResult.inconclusive(raison)
- socket fermée, timeout, injoignable -> ScannerUnavailableException

Un dépassement de limite n'est PAS une infection : jamais INFECTED.

LES DEUX LIMITES DÉCLARÉES
maxScannableSize() et maxConcurrentScans() viennent de la config de
cette implémentation, pas du service. Ne récite aucun chiffre de
mémoire : lis-les sur l'image épinglée avant de les figer.

  docker run --rm clamav/clamav:1.5.4 clamconf \
    | grep -iE 'MaxFileSize|MaxScanSize|StreamMaxLength|AlertExceedsMax|MaxThreads'

CLAMD.CONF MONTÉ EN VOLUME
AlertExceedsMax TRUE est OBLIGATOIRE. Sans lui, un fichier qui dépasse
MaxFileSize ou MaxScanSize n'est pas analysé et clamd répond OK quand
même : mon invariant serait vrai dans le code et faux dans les faits.
C'est le troisième appui de l'invariant, pas un réglage de confort.

Aligner ensuite les trois plafonds sur ma taille max : StreamMaxLength,
MaxFileSize et MaxScanSize doivent tous être au-dessus, sinon je
fabrique une catégorie de fichiers qui échouent systématiquement.

Ne réécris pas le clamd.conf de zéro : pars de celui que l'image
embarque et ne change que ce que tu veux changer.

CÂBLAGE
@ConditionalOnProperty sur sfs.antivirus.implementation=clamav. Le
stub reste dans src/test et n'apparaît jamais comme valeur possible.

Pas de synchronized autour de l'appel bloquant.

DOCKER-COMPOSE
clamav/clamav:1.5.4 et mysql:8.4, healthchecks sur les deux. ClamAV met
plusieurs minutes à démarrer la première fois : le healthcheck doit
avoir un start_period généreux, et le backend doit attendre
service_healthy.

TESTS
Sans clamd réel, teste le protocole contre une fausse socket serveur :
la trame émise (en-tête, préfixes de taille, zéro final), les cinq
correspondances réponse vers verdict, l'arrêt anticipé avec écriture
qui casse, et le timeout qui donne bien ScannerUnavailableException.
```

```
Lance docker compose up et vérifie que tout démarre vraiment.
Ne me donne aucune commande que tu n'as pas exécutée toi-même.
```

---

## Le scanner HTTP

```
GO pour la seconde implémentation d'antivirus.

L'énoncé dit "un antivirus disponible via une API" sans préciser le
protocole. Je veux couvrir les deux lectures et démontrer que mon
abstraction tient.

HttpScannerImpl : POST multipart en STREAMING vers une API REST
configurable, réponse interprétée code par code. Même contrat que
ClamAvScannerImpl, aucun changement dans le métier. Même niveau de
finition, tests et gestion d'erreur compris.

Tranche d'abord les limites, qui étaient en suspens dans CLAUDE.md :
taille maximale analysable et scans concurrents.

Attention à trois choses :
- le multipart doit streamer, pas charger le fichier en mémoire.
  Prouve-le par un test, ne l'affirme pas.
- une vraie API demanderait une clé. Prévois-la, sans la rendre
  obligatoire.
- rien ne borne la requête côté distant : compte les octets toi-même,
  sinon une API qui tronque et répond OK produirait un CLEAN sur du
  contenu non analysé.

@ConditionalOnProperty sur sfs.antivirus.implementation=http, et une
garde qui rejette toute valeur autre que clamav ou http avec un
message qui la nomme.

Les valeurs de l'image visée, lis-les dans l'image comme pour ClamAV.
```

---

## Les endpoints de suivi

```
GO pour /status, /rescan et la liste paginée.

GET /api/files/{id}/status : status, raison, dates, scanAttempts,
leaseExpiries. La raison compte autant que l'état : UNSCANNABLE
recouvre une limite dépassée, une archive chiffrée et un contenu
illisible.

POST /api/files/{id}/rescan : 202 depuis SCAN_FAILED_EXHAUSTED avec
les compteurs remis à zéro, 409 depuis tout autre état en nommant
l'état courant, 404 si inconnu.

GET /api/files : liste paginée, filtrable par statut.

Aucune logique métier dans les controllers : ils valident, délèguent,
mappent. Records pour les DTO, aucune entité exposée.

Trois points à me demander avant de coder.
```

---

## L'interface et le CORS

```
GO pour le front.

React 18 + TypeScript + Vite + Material UI. C'est un poste full
stack, le front est jugé aussi : je ne veux pas d'une page brute.

Une seule page :
- dépôt par glisser-déposer, avec la progression du transfert
- tableau paginé et filtrable, la pagination et les filtres servis
  par l'API, jamais calculés sur les lignes affichées
- badge par état, avec une info-bulle qui explique l'état
- téléchargement grisé hors CLEAN
- relance sur les scans épuisés
- polling tant qu'un fichier bouge, et qui s'arrête quand plus rien
  ne bouge

Découpe en composants, services, hooks, types et handlers. Pas de
composant énorme. Aucun composant ne doit inspecter une erreur HTTP
brute : un handler la traduit une fois en type applicatif.
```

```
GO pour le CORS côté back.

Le navigateur appelle l'API directement, sans proxy. Le backend
déclare les origines autorisées, jamais *. Méthodes GET et POST
seulement.

Expose explicitement Content-Disposition et Retry-After : sans ça le
navigateur les cache au JavaScript et le front ne peut pas lire le nom
du fichier qu'il télécharge.

Puis les tests : preflight accepté sur une origine déclarée, refusé
sur une origine inconnue, refusé sur une méthode non autorisée, et
les deux en-têtes bien exposés.
```

```
Le rendu du front est trop pauvre. Donne-moi une direction visuelle,
avec un plan avant de coder, et sans toucher à la logique.

Tout style conditionnel passe par styled(), jamais par sx : un style
piloté par une prop appartient au composant stylé, pas au JSX.

Et vérifie le mode sombre jeton par jeton, pas au jugé.
```

---

## La relecture critique

```
Relis tout le code du projet et sois critique.

1. Où un fichier pourrait-il être chargé en mémoire sans que je
   m'en rende compte ?
2. Par quel chemin l'invariant (aucun fichier non CLEAN servi)
   pourrait-il être contourné ?
3. Quelles ressources ne sont pas fermées sur chemin d'erreur ?
4. Qu'est-ce qui casserait sous charge ?

Ne me dis pas que c'est bien si ça ne l'est pas.
```

---

## Le README

```
Rédige le README.md final à partir du code.

Sections : le problème compris, démarrage rapide avec 2 exemples
curl, hypothèses formulées, architecture (schéma + machine à états),
choix techniques (décision / pourquoi / alternative écartée),
limites assumées, pistes d'évolution.

Ton factuel. Pas d'emoji, pas de superlatif. En français.
N'utilise jamais de tiret cadratin, uniquement des tirets simples.
```
