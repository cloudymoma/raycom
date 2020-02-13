-- MySQL 8.0+

create database if not exists gcp;
use gcp;

create table if not exists t_dim1 (
    id binary(16),
    created_at timestamp not null default now(),
    updated_at timestamp not null default now() on update now(),
    dim1 varchar(64) not null,
    dim1_val varchar(128) not null, 
    primary key (id),
    constraint uc_dim1 unique (dim1)
) 
character set utf8mb4 collate utf8mb4_unicode_ci;

-- init dim1 table
insert into t_dim1(id, dim1, dim1_val)
values  (uuid_to_bin(uuid()), 'bindigo', 'rogue'),
        (uuid_to_bin(uuid()), 'bindiego', 'warrior'),
        (uuid_to_bin(uuid()), 'ivy', 'druid'),
        (uuid_to_bin(uuid()), 'duelaylowmow', 'priest');

-- verify dim1 table
select 
    bin_to_uuid(id) as id,
    created_at,
    updated_at,
    dim1,
    dim1_val
from t_dim1
limit 10;
