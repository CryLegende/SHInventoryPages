<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHInventoryPages

*Pages d'inventaire supplémentaires, nombre selon la permission, sauvegardées en base de données.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Pages d'inventaire supplémentaires, nombre selon la permission, sauvegardées en base de données.

- **Débordement multi-piles** : un objet donné à un joueur dont l'inventaire porté est plein (`/give`, reste d'un don de plugin à ses pieds, bloc qu'il vient de casser) est découpé selon sa taille de pile (`max_stack_size` compris) et rangé dans les pages suivantes : piles partielles puis emplacements vides, page par page. Rien n'est jamais rangé deux fois ni perdu : ce qui ne tient nulle part reste au sol. Décidé avant WildStacker, qui n'empile que le reste ; les piles gérées par WildStacker (quantité cachée) ne sont jamais touchées.
- **Sélecteur verrouillé** : aucun clic, glisser, touche, échange de main, jet, pose, cadre, artisanat ou entonnoir ne le déplace ; il porte le numéro de la page que représente l'inventaire porté (reprise exacte après un arrêt brutal ou une restauration d'inventaire).
- **Stockage** : chaque page encodée par `ItemStack#serializeAsBytes` (NBT complet, composants et PDC conservés), anciennes lignes toujours lues ; seules les pages modifiées sont écrites, hors du thread principal, dans l'ordre par joueur. Base injoignable au départ d'un joueur : nouveaux essais puis copie dans `unsaved/`, reprise automatique à sa connexion suivante.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `PlaceholderAPI`, `SHNetwork` et `HuskSync` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent les pages d'un joueur. Il faut une base MariaDB ou MySQL, la même (`database.mysql`) sur tous les serveurs, et **HuskSync** pour l'inventaire porté : la page portée vit dans l'inventaire du joueur, que HuskSync synchronise, les autres pages sont dans la base.

Trois mécanismes se complètent, le plugin attend les deux premiers avant de lire quoi que ce soit :

- **Barrière de SHNetwork** (les pages en base) : au départ d'un joueur, l'écriture de ses pages est inscrite auprès de SHNetwork, qui ne publie « sauvegardé » qu'une fois cette écriture validée (ou en échec). Le serveur d'arrivée attend cette confirmation avant de lire les pages, jusqu'à `reseau.attente-max-secondes` (serveur de départ arrêté brutalement : il charge alors les pages telles que la base les a).
- **Verrou de HuskSync** (l'inventaire porté) : HuskSync verrouille le joueur à son arrivée et lui applique son inventaire (page portée et sélecteur compris) dès que le serveur de départ l'a enregistré. Les deux plugins n'ont aucun verrou en commun, ils se déclenchent séparément au même départ. Le plugin attend l'événement de fin de synchronisation de HuskSync avant de lire le sélecteur de l'inventaire porté et de capturer la page portée : le faire avant prendrait l'inventaire local périmé pour la page portée, qui serait ensuite écrite en base. Si HuskSync ne termine pas en `reseau.attente-husksync-secondes`, le joueur est déconnecté sans que ses pages soient chargées (comme quand la base est injoignable).
- **Version de session** (le dernier qui écrit ne gagne plus) : chaque chargement augmente `shinventorypages_sync.version` dans la même transaction que la lecture des pages. Chaque écriture relit cette ligne sous verrou et n'est acceptée que si la version est celle de son chargement. Une écriture d'un serveur que l'autre a dépassé (barrière expirée pendant une panne de base, par exemple) est refusée et gardée dans `unsaved/` sous un fichier `.superseded`, jamais reprise toute seule (`/ship recover <fichier> force` après examen).

À l'arrêt d'un serveur, la tâche de départ confiée à SHNetwork capture et écrit les pages de chaque joueur encore connecté avant que le serveur n'annonce son départ : un joueur renvoyé tout de suite sur l'autre serveur y retrouve ses pages à jour.

Copies de secours : l'écriture d'un départ est réessayée pendant `performance.end_of_session_retry_seconds`, puis copiée dans la table `shinventorypages_unsaved` (lisible par tous les serveurs), ou sur disque si la base ne répond pas. Une copie porte la version de la session qui l'a produite et n'est reprise à la connexion que si aucun autre serveur n'a repris le joueur depuis.

Tables (créées au démarrage, ou à la main avec `sql/schema.sql`) : `shinventorypages_sync` et `shinventorypages_unsaved`, dans la base des pages. Sans SHNetwork, ou avec SQLite, le plugin se comporte comme avant : rien n'est créé ni lu.

## API

`SHInventoryPages#getApi()` ou `Bukkit.getServicesManager().load(ISHInventoryPagesAPI.class)` (thread principal) :

| Méthode | Rôle |
|---|---|
| `addItems(player, items...)` | à la place d'`Inventory#addItem` : inventaire porté puis pages suivantes ; rend les restes |
| `removeItems(player, items...)` / `countItems(player, item)` | sur l'inventaire porté et les pages accessibles |
| `getFreeSpace(player, item)` | place restante sur toutes les pages |
| `blockPageSwitching` / `unblockPageSwitching` | fige les pages (parcours, mini-jeux) |
| `PlayerPageSwitchEvent` / `PlayerItemsStoredEvent` | changement de page (annulable sauf forcé), objets rangés dans une page |

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shinventorypages reload` | `/ship` | Recharge réglages et messages (pas la base). | `shinventorypages.admin` |
| `/shinventorypages saveall` | `/ship` | Écrit les pages de tous les joueurs. | `shinventorypages.admin` |
| `/shinventorypages info <joueur>` | `/ship` | Pages d'un joueur : contenu, pages cachées et illisibles. | `shinventorypages.admin` |
| `/shinventorypages recover <fichier> [force]` | `/ship` | Réimporte un fichier de `unsaved/` (joueur hors ligne). Mode réseau : refuse un fichier d'une session dépassée par un autre serveur, sauf `force`. | `shinventorypages.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shinventorypages.*` | Grants all permissions for SHInventoryPages. | `op` |
| `shinventorypages.admin` | Allows access to admin commands like reload. | `op` |
| `shinventorypages.pages.*` | Wildcard for page amount permissions (e.g., shinventorypages.pages.5). | `false` |
| `shinventorypages.pages.<number>` | Grants access up to a specific number of pages. | `false` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHInventoryPages/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

Tables du mode réseau : <a href="sql/schema.sql">sql/schema.sql</a>.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
