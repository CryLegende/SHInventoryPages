-- Tables du mode réseau et des réglages de pages de SHInventoryPages. Le plugin les crée seul au démarrage si
-- l'utilisateur a le droit CREATE ; ce fichier sert à les créer à la main. Elles vont dans la base des pages
-- (database.mysql.database de config.yml, la même sur tous les serveurs) : la version de session et les pages sont
-- écrites dans la même transaction.
--
-- Utilisation : remplacer <base> par le nom de cette base, puis  mysql -u root < sql/schema.sql

USE <base>;

-- Dernière session de chaque joueur. Un chargement augmente la version ; une écriture n'est acceptée que si la version
-- est celle de son chargement.
CREATE TABLE IF NOT EXISTS shinventorypages_sync (
    uuid    VARCHAR(36) NOT NULL,   -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    version BIGINT      NOT NULL,   -- version de la dernière session chargée
    server  VARCHAR(64) NOT NULL,   -- serveur qui l'a chargée
    updated BIGINT      NOT NULL,   -- millisecondes depuis 1970
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Copies de secours d'écritures échouées (même contenu qu'un fichier de plugins/SHInventoryPages/unsaved/).
CREATE TABLE IF NOT EXISTS shinventorypages_unsaved (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    uuid       VARCHAR(36) NOT NULL,
    epoch      BIGINT      NOT NULL,           -- version de la session qui les a produites
    server     VARCHAR(64) NOT NULL,
    created    BIGINT      NOT NULL,
    superseded TINYINT     NOT NULL DEFAULT 0, -- 1 : session dépassée par un autre serveur, jamais reprise toute seule
    data       LONGTEXT    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Réglages de chaque page choisis par le joueur dans le menu des pages. Une page sans réglage n'a pas de ligne.
CREATE TABLE IF NOT EXISTS shinventorypages_page_settings (
    uuid        VARCHAR(36) NOT NULL,
    page_number INT         NOT NULL,
    icon        TEXT,                           -- objet dont la page prend l'apparence (Base64), NULL = sélecteur par défaut
    reserved    TINYINT     NOT NULL DEFAULT 0, -- 1 : page sautée par le rangement automatique
    PRIMARY KEY (uuid, page_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
