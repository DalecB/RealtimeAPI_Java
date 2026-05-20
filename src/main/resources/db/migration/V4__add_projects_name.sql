ALTER TABLE projects ADD COLUMN name VARCHAR(100);

UPDATE projects
SET name = 'project-' || id::text
WHERE name IS NULL;

ALTER TABLE projects ALTER COLUMN name SET NOT NULL;

ALTER TABLE projects ADD CONSTRAINT uk_projects_name UNIQUE (name);