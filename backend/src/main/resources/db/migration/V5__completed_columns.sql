ALTER TABLE board_column ADD COLUMN is_done BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE board_column SET is_done=TRUE WHERE name='Concluído';
