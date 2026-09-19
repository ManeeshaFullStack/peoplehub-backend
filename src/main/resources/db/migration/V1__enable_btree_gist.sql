-- V1: enable btree_gist.
-- Needed from B4 for exclusion constraints on tstzrange (no overlapping attendance sessions, Spec 2, 4.2).
-- btree_gist is a trusted extension (PostgreSQL 13+), so no superuser is required.
CREATE EXTENSION IF NOT EXISTS btree_gist;
