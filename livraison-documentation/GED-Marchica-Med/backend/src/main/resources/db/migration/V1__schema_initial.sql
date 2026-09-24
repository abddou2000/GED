-- =====================================================================
--  GED Marchica Med — schema initial
-- =====================================================================
--  POURQUOI CE FICHIER EXISTE
--  Le profil `prod` demarre avec `ddl-auto: validate` : Hibernate compare le
--  schema attendu a celui de la base et REFUSE de demarrer s'il manque quoi
--  que ce soit. Or aucun outil de migration n'existait, et aucun script n'etait
--  fourni : sur une base vierge, l'application echouait au demarrage avec
--  `SchemaManagementException: missing table [access_groups]`, sans qu'aucune
--  documentation n'indique comment creer le schema. Une premiere installation
--  chez un client etait donc impossible.
--
--  Ce fichier est genere depuis les entites JPA (dialecte MySQL), puis
--  dedoublonne. Il ne se modifie pas a la main : toute evolution du modele
--  donne une migration SUIVANTE (V2, V3...), jamais une retouche de celle-ci —
--  Flyway refuserait un fichier deja applique dont l'empreinte a change.
-- =====================================================================

-- ---------- Tables ----------

create table access_groups (
        deleted bit not null,
        droit_access bit not null,
        droit_ajouter_version bit not null,
        droit_deplacer bit not null,
        droit_lecture bit not null,
        droit_modifier bit not null,
        droit_supprimer bit not null,
        droit_uploader bit not null,
        droit_verrouiller_deverrouiller bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(255) not null,
        name varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table comptes_utilisateurs (
        actif bit not null,
        derniere_connexion datetime(6),
        employe_id bigint not null,
        id bigint not null auto_increment,
        mot_de_passe varchar(100) not null,
        email varchar(190) not null,
        primary key (id)
    ) engine=InnoDB;

create table document_index_values (
        created_at datetime(6),
        document_id bigint not null,
        id bigint not null auto_increment,
        index_field_id bigint not null,
        updated_at datetime(6),
        valeur text,
        primary key (id)
    ) engine=InnoDB;

create table document_versions (
        is_default bit not null,
        created_at datetime(6),
        document_id bigint not null,
        id bigint not null auto_increment,
        size_ko bigint,
        updated_at datetime(6),
        extension varchar(255),
        file_name varchar(255) not null,
        file_path varchar(255) not null,
        observation text,
        primary key (id)
    ) engine=InnoDB;

create table documents_file (
        active bit not null,
        deleted bit not null,
        expiration_date date,
        is_locked boolean default false not null,
        created_at datetime(6),
        created_by_employe_id bigint,
        id bigint not null auto_increment,
        size_ko bigint,
        type_document_id bigint not null,
        updated_at datetime(6),
        workspace_id bigint not null,
        extension varchar(255),
        file_name varchar(255),
        file_path varchar(255),
        name varchar(255) not null,
        reference varchar(255),
        primary key (id)
    ) engine=InnoDB;

create table employes (
        has_user bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        first_name varchar(255) not null,
        last_name varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table etiquettes (
        deleted bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(255) not null,
        couleur varchar(255) not null,
        tag varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table indices (
        deleted bit not null,
        index_de_groupage bit not null,
        indexe_pour_recherche bit not null,
        obligatoire bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(255) not null,
        nom_index varchar(255) not null,
        valeur_par_defaut varchar(255),
        valeurs text,
        type_champs enum ('DATE','LISTE','NOMBRE','TEXTE') not null,
        primary key (id)
    ) engine=InnoDB;

create table pivot_document_etiquettes (
        document_id bigint not null,
        etiquette_id bigint not null,
        primary key (document_id, etiquette_id)
    ) engine=InnoDB;

create table pivot_employe_groups (
        access_group_id bigint not null,
        employe_id bigint not null,
        primary key (access_group_id, employe_id)
    ) engine=InnoDB;

create table pivot_plan_d_indexation_indices (
        position integer not null,
        index_id bigint not null,
        plan_d_indexation_id bigint not null,
        primary key (position, plan_d_indexation_id)
    ) engine=InnoDB;

create table pivot_workspace_groups (
        access_group_id bigint not null,
        workspace_id bigint not null,
        primary key (access_group_id, workspace_id)
    ) engine=InnoDB;

create table plan_d_indexations (
        deleted bit not null,
        majuscule bit not null,
        manuel bit not null,
        mode_indexation bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        charte_nommage varchar(2000),
        code varchar(255) not null,
        nom_du_plan varchar(255) not null,
        separateur varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table type_de_documents (
        deleted bit not null,
        taille_max_mo integer not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        plan_d_indexation_id bigint,
        updated_at datetime(6),
        workspace_id bigint not null,
        code varchar(255) not null,
        description text not null,
        type_autorise varchar(255),
        type_de_document varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table work_spaces (
        deleted bit not null,
        created_at datetime(6),
        employe_id bigint not null,
        id bigint not null auto_increment,
        parent_workspace_id bigint,
        updated_at datetime(6),
        workflow_ged_id bigint not null,
        code varchar(255) not null,
        description varchar(255),
        name varchar(255) not null,
        status enum ('ACTIF','ARCHIVE','INACTIF') not null,
        primary key (id)
    ) engine=InnoDB;

create table workflow_ged (
        deleted bit not null,
        created_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        name varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

create table workflow_ged_signatures (
        step_order integer not null,
        created_at datetime(6),
        document_id bigint not null,
        employe_id bigint not null,
        id bigint not null auto_increment,
        signed_at datetime(6),
        updated_at datetime(6),
        motif varchar(255),
        step_label varchar(255),
        status enum ('PENDING','REJECTED','SIGNED') not null,
        primary key (id)
    ) engine=InnoDB;

create table workflow_ged_steps (
        step_order integer not null,
        created_at datetime(6),
        employe_id bigint not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        workflow_ged_id bigint not null,
        label varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

-- ---------- Cles etrangeres et contraintes d'unicite ----------

alter table access_groups 
       add constraint UKhvioug7dm5n1yki1a8fjil0bq unique (code);

alter table access_groups 
       add constraint UKhyfvgm64p0c71lu89or53avgr unique (name);

alter table comptes_utilisateurs 
       add constraint idx_compte_email unique (email);

alter table comptes_utilisateurs 
       add constraint UKhcg2gn592g1kfx5qd4h146a5x unique (employe_id);

alter table document_index_values 
       add constraint UK93wyfnsva2fal1hiigieqfmrg unique (document_id, index_field_id);

alter table etiquettes 
       add constraint UKqpv4qyrl1cg6jv9erhfkp487k unique (code);

alter table indices 
       add constraint UK7ifeigguyn1lbaehydyeacs6s unique (code);

alter table plan_d_indexations 
       add constraint UKrthy7u605jman0oenjxcycpm unique (code);

alter table type_de_documents 
       add constraint UKko5l2htnfqf4c54k2pa9j19jp unique (code);

alter table work_spaces 
       add constraint UK2pbfpjiya1giqy03rxp7isn37 unique (code);

alter table comptes_utilisateurs 
       add constraint FKg7mtep8ftv2pnhvhdyrb5773u 
       foreign key (employe_id) 
       references employes (id);

alter table document_index_values 
       add constraint FKpnouhpvlkim0vxbajlpno1f9d 
       foreign key (document_id) 
       references documents_file (id);

alter table document_index_values 
       add constraint FKj2kd1fap4y3i7cd6pk9wdo5io 
       foreign key (index_field_id) 
       references indices (id);

alter table document_versions 
       add constraint FKafw8hq5fonpt4mbnbf8hvlj0w 
       foreign key (document_id) 
       references documents_file (id);

alter table documents_file 
       add constraint FKktp3qdn9a3lm6171eumnigq8d 
       foreign key (created_by_employe_id) 
       references employes (id);

alter table documents_file 
       add constraint FKqt268k1kp521fnfsb7yaxxh93 
       foreign key (type_document_id) 
       references type_de_documents (id);

alter table documents_file 
       add constraint FK13u0n53entgg4b4fmqltkxq8m 
       foreign key (workspace_id) 
       references work_spaces (id);

alter table pivot_document_etiquettes 
       add constraint FKgx5w2fot0rpiq6q01itrkyqd0 
       foreign key (etiquette_id) 
       references etiquettes (id);

alter table pivot_document_etiquettes 
       add constraint FKqbsn9wrnjcwym2m9p4nyy1he3 
       foreign key (document_id) 
       references documents_file (id);

alter table pivot_employe_groups 
       add constraint FKml37pnhsetifw6spd98pbdhp1 
       foreign key (employe_id) 
       references employes (id);

alter table pivot_employe_groups 
       add constraint FK8xuan0pk4w6jkxax2uf3b3a1a 
       foreign key (access_group_id) 
       references access_groups (id);

alter table pivot_plan_d_indexation_indices 
       add constraint FKqxfpwahocln76exhsua2qu8c3 
       foreign key (index_id) 
       references indices (id);

alter table pivot_plan_d_indexation_indices 
       add constraint FK6unhcykxc6hpyca9alnfeic0n 
       foreign key (plan_d_indexation_id) 
       references plan_d_indexations (id);

alter table pivot_workspace_groups 
       add constraint FKtd425tr07iwknv1tcs8d5ne1i 
       foreign key (workspace_id) 
       references work_spaces (id);

alter table pivot_workspace_groups 
       add constraint FK51a6sfe7v0pes5k94aw580u4u 
       foreign key (access_group_id) 
       references access_groups (id);

alter table type_de_documents 
       add constraint FKgx6lay1xmxa6f9oixdum0u79p 
       foreign key (plan_d_indexation_id) 
       references plan_d_indexations (id);

alter table type_de_documents 
       add constraint FKlq4fr8dqlflo14fh7op3u4nfm 
       foreign key (workspace_id) 
       references work_spaces (id);

alter table work_spaces 
       add constraint FKi4l87o35wsoleyv4l86yfl1i6 
       foreign key (employe_id) 
       references employes (id);

alter table work_spaces 
       add constraint FKla5vtw0o7opixr35p8kdckx6 
       foreign key (parent_workspace_id) 
       references work_spaces (id);

alter table work_spaces 
       add constraint FKooa9y3s3i2awldr541li0r2fc 
       foreign key (workflow_ged_id) 
       references workflow_ged (id);

alter table workflow_ged_signatures 
       add constraint FKtgdabne9g9slkcwc93c4j7y8e 
       foreign key (document_id) 
       references documents_file (id);

alter table workflow_ged_signatures 
       add constraint FK784piy61bwvigbi4bh1lcbk88 
       foreign key (employe_id) 
       references employes (id);

alter table workflow_ged_steps 
       add constraint FKmmd89fng7p3wt2wo5om9simmi 
       foreign key (employe_id) 
       references employes (id);

alter table workflow_ged_steps 
       add constraint FK8l8uvr2wttapdb6or55c9xnv0 
       foreign key (workflow_ged_id) 
       references workflow_ged (id);

