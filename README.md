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

- Optionnelles : `PlaceholderAPI`

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
| `/shinventorypages recover <fichier> [force]` | `/ship` | Réimporte un fichier de `unsaved/` (joueur hors ligne). | `shinventorypages.admin` |

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
