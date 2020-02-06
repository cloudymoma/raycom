-- MySQL 8.0+

create database if not exists gcp;
use gcp;

create table if not exists t_dim1 (
    id binary(16) primary key,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now() on update now(),
    dim1 varchar(64) character set utf8mb4 collate utf8mb4_unicode_ci,
    dim1_val varchar(128) character set utf8mb4 collate utf8mb4_unicode_ci
) 
character set utf8mb4 collate utf8mb4_unicode_ci;

-- init dim1 table
insert into t_dim1(id, dim1, dim1_val)
values  (uuid_to_bin(uuid()), 'bindigo', 'warrior'),
        (uuid_to_bin(uuid()), 'bindiego', 'rogue'),
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
