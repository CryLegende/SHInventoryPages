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
- **Ramassage vers les pages** : un objet au sol que le joueur touche alors que son inventaire porté n'en peut rien prendre (butin d'une créature, objet jeté, pile empilée par WildStacker) est rangé dans ses autres pages au lieu de rester au sol. Les règles de ramassage de l'île (SuperiorSkyblock2) et le drapeau `item-pickup` des régions WorldGuard s'appliquent comme au ramassage normal. WildStacker relit la quantité de la pile après le ramassage : rien n'est compté deux fois.
- **Historique des pages** : une copie de toutes les pages est gardée en base aux mêmes moments qu'InventoryRollbackPlus pour l'inventaire porté (connexion, déconnexion, mort), toutes les 30 minutes si les pages ont changé, et avant toute opération sur toutes les pages (`/sell allpages` de SHSell, restauration). `/shinventorypages history <joueur>` les liste et affiche chaque page ; avec la permission de restauration, un clic copie un objet, shift + clic sur le bouton remplace la page du joueur (l'état d'avant est lui-même gardé).
- **Menu des pages** (`/pages`, ou shift + clic sur le sélecteur) : une case par page, avec ses emplacements occupés et ses objets les plus nombreux ; un clic gauche porte la page. Le menu reste ouvert : l'inventaire du joueur, affiché en dessous, devient celui de la page et s'utilise comme devant un coffre (rien ne peut entrer dans le menu ni en sortir). Les pages pas encore débloquées sont affichées grisées. Au survol du sélecteur, les touches 1 à 9 portent directement la page de ce numéro, et `/pages <numéro>` fait de même. Le clic molette n'est pas utilisé : le client ne l'envoie au serveur qu'en mode créatif.
- **Réglages de chaque page par le joueur** (clic droit sur une page du menu) : une **icône**, prise sur un objet de son inventaire sans le consommer, un **nom**, saisi dans un dialogue (clients 1.21.6 et plus) et affiché tel quel sur le sélecteur et dans le menu, et la **réserve**, qui sort la page du rangement automatique (débordement, ramassage vers les pages, dons des plugins) tant qu'elle n'est pas portée. Quand la page est portée, le sélecteur prend l'apparence de son icône (modèle d'objet, texture d'une tête) tout en restant l'objet de `switcher_item.material` : aucun plugin, aucune recette, aucune vente ne peut le prendre pour l'objet qu'il imite. Un client antérieur à la 1.21.4 voit le sélecteur par défaut ; dans le menu, l'icône est le vrai objet, visible par tous.
- **Rendu du dialogue du nom** : le titre est la première ligne du texte (la barre de titre ne garde que l'icône d'avertissement du client) et le joueur est aveuglé (effet Cécité) tant que le dialogue est affiché, le texte se lit sur fond noir. L'effet est retiré dès que le joueur clique un bouton du dialogue, sinon dès qu'il tourne la caméra, se déplace ou ouvre un inventaire ; un joueur déjà aveugle pour une autre raison n'est pas touché. Option `blind_during_dialogs` (`true` par défaut).
- **Sélecteur verrouillé** : aucun clic, glisser, touche, échange de main, jet, pose, cadre, artisanat ou entonnoir ne le déplace ; il porte le numéro de la page que représente l'inventaire porté (reprise exacte après un arrêt brutal ou une restauration d'inventaire).
- **Stockage** : chaque page encodée par `ItemStack#serializeAsBytes` (NBT complet, composants et PDC conservés), anciennes lignes toujours lues ; seules les pages modifiées sont écrites, hors du thread principal, dans l'ordre par joueur. Base injoignable au départ d'un joueur : nouveaux essais puis copie dans `unsaved/`, reprise automatique à sa connexion suivante.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `ViaVersion` (version du client, pour le dialogue du nom d'une page), `PlaceholderAPI`, `WorldGuard` (drapeau `item-pickup` respecté par le ramassage vers les pages), `SHNetwork` et `HuskSync` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent les pages d'un joueur. Il faut une base MariaDB ou MySQL, la même (`database.mysql`) sur tous les serveurs, et **HuskSync** pour l'inventaire porté : la page portée vit dans l'inventaire du joueur, que HuskSync synchronise, les autres pages sont dans la base.

Trois mécanismes se complètent, le plugin attend les deux premiers avant de lire quoi que ce soit :

- **Barrière de SHNetwork** (les pages en base) : au départ d'un joueur, l'écriture de ses pages est inscrite auprès de SHNetwork, qui ne publie « sauvegardé » qu'une fois cette écriture validée (ou en échec). Le serveur d'arrivée attend cette confirmation avant de lire les pages, jusqu'à `reseau.attente-max-secondes` (serveur de départ arrêté brutalement : il charge alors les pages telles que la base les a).
- **Verrou de HuskSync** (l'inventaire porté) : HuskSync verrouille le joueur à son arrivée et lui applique son inventaire (page portée et sélecteur compris) dès que le serveur de départ l'a enregistré. Les deux plugins n'ont aucun verrou en commun, ils se déclenchent séparément au même départ. Le plugin attend l'événement de fin de synchronisation de HuskSync avant de lire le sélecteur de l'inventaire porté et de capturer la page portée : le faire avant prendrait l'inventaire local périmé pour la page portée, qui serait ensuite écrite en base. Si HuskSync ne termine pas en `reseau.attente-husksync-secondes`, le joueur est déconnecté sans que ses pages soient chargées (comme quand la base est injoignable).
- **Version de session** (le dernier qui écrit ne gagne plus) : chaque chargement augmente `shinventorypages_sync.version` dans la même transaction que la lecture des pages. Chaque écriture relit cette ligne sous verrou et n'est acceptée que si la version est celle de son chargement. Une écriture d'un serveur que l'autre a dépassé (barrière expirée pendant une panne de base, par exemple) est refusée et gardée dans `unsaved/` sous un fichier `.superseded`, jamais reprise toute seule (`/shinventorypages recover <fichier> force` après examen).

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
| `applyToStoredPages(player, operation, action)` | applique une opération faite pour l'inventaire porté (vente de tout, tri...) à chaque autre page, posée le temps d'un tick comme inventaire porté ; copie dans l'historique juste avant |
| `blockPageSwitching` / `unblockPageSwitching` | fige les pages (parcours, mini-jeux) |
| `PlayerPageSwitchEvent` / `PlayerItemsStoredEvent` | changement de page (annulable sauf forcé), objets rangés dans une page |

## Commandes

Joueurs :

| Commande | Description | Permission |
|---|---|---|
| `/pages` (alias `/page`) | Menu des pages : clic gauche pour porter une page, clic droit pour son icône, son nom et sa réserve | `shinventorypages.menu`, réglages : `shinventorypages.menu.customize` |
| `/pages <numéro>` | Porte directement cette page | `shinventorypages.menu` |

Sélecteur : clic droit = page suivante, clic gauche = page précédente, shift + clic = menu des pages, touche 1 à 9 au survol = page de ce numéro.

Racine d'administration `/shinventorypages`, alias `/ship`. Chaque sous-commande a sa permission ; sans argument, une ligne d'usage ne liste que celles que l'expéditeur peut utiliser.

| Commande | Description | Permission |
|---|---|---|
| `/shinventorypages reload` | Recharge réglages et messages (pas la base ni la section `reseau`) ; un fichier invalide annule le rechargement | `shinventorypages.admin.reload` |
| `/shinventorypages saveall` | Écrit les pages de tous les joueurs | `shinventorypages.admin.saveall` |
| `/shinventorypages info <joueur>` | Pages d'un joueur : contenu, pages cachées et illisibles | `shinventorypages.admin.info` |
| `/shinventorypages recover <fichier> [force]` | Réimporte un fichier de `unsaved/` (joueur hors ligne). Mode réseau : refuse un fichier d'une session dépassée par un autre serveur, sauf `force`. | `shinventorypages.admin.recover` |
| `/shinventorypages history <joueur>` | Historique de toutes les pages d'un joueur, connecté ou non | `shinventorypages.history` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shinventorypages.*` | Grants all permissions for SHInventoryPages. | `op` |
| `shinventorypages.admin` | Toutes les sous-commandes d'administration (parent des quatre suivantes) | `op` |
| `shinventorypages.admin.reload`, `.saveall`, `.info`, `.recover` | Une permission par sous-commande | `false` |
| `shinventorypages.history` | Ouvre l'historique des pages d'un joueur (`/shinventorypages history <joueur>`) | `op` |
| `shinventorypages.history.restore` | Copier un objet de l'historique, restaurer une page | `op` |
| `shinventorypages.menu` | Menu des pages (`/pages`, shift + clic sur le sélecteur) et `/pages <numéro>` | `true` |
| `shinventorypages.menu.customize` | Icône, nom et réserve de chaque page, depuis le menu | `true` |
| `shinventorypages.pages.*` | Plafond de pages (`max_possible_pages`) | `false` |
| `shinventorypages.pages.<n>` | Donne `n` pages au joueur (ex. `shinventorypages.pages.5`) ; non déclaré, géré dynamiquement | `op` (non déclaré) |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHInventoryPages/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Tables du mode réseau et des réglages de pages (`shinventorypages_page_settings`, créée au démarrage) : <a href="sql/schema.sql">sql/schema.sql</a>. Sans cette dernière, seuls l'icône, le nom et la réserve des pages manquent.

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
