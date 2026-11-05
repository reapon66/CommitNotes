CREATE TABLE task_assignee (task_id UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE, PRIMARY KEY(task_id,user_id));
INSERT INTO task_assignee(task_id,user_id) SELECT id,assignee_id FROM task WHERE assignee_id IS NOT NULL;
ALTER TABLE task DROP COLUMN assignee_id;
CREATE INDEX task_assignee_user ON task_assignee(user_id);

CREATE TABLE task_comment (id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
 author_id UUID NOT NULL REFERENCES app_user(id), body VARCHAR(5000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX task_comment_task ON task_comment(task_id,created_at);

CREATE TABLE notification (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 project_id UUID REFERENCES project(id) ON DELETE CASCADE, task_id UUID REFERENCES task(id) ON DELETE CASCADE,
 actor_id UUID REFERENCES app_user(id) ON DELETE SET NULL, title VARCHAR(200) NOT NULL, body VARCHAR(1000) NOT NULL DEFAULT '',
 url VARCHAR(300) NOT NULL DEFAULT '/', read_at TIMESTAMPTZ, pushed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX notification_user ON notification(user_id,created_at DESC);
CREATE INDEX notification_push ON notification(created_at) WHERE pushed_at IS NULL;

CREATE INDEX activity_entity ON activity(entity_id,created_at DESC);

ALTER TABLE board_column ADD COLUMN color VARCHAR(7) NOT NULL DEFAULT '#64748b';
UPDATE board_column SET color=CASE WHEN is_done THEN '#22c55e' ELSE (ARRAY['#64748b','#3b82f6','#f59e0b','#8b5cf6','#ec4899','#14b8a6'])[1+position%6] END;

ALTER TABLE tag ADD COLUMN color VARCHAR(7) NOT NULL DEFAULT '#6366f1';
UPDATE tag t SET color=p.color FROM (SELECT id,(ARRAY['#6366f1','#f97316','#22c55e','#ec4899','#0ea5e9','#eab308','#8b5cf6','#ef4444','#14b8a6','#64748b'])[1+((row_number() OVER (PARTITION BY project_id ORDER BY name))-1)::int%10] AS color FROM tag) p WHERE p.id=t.id;
