-- 回退时也保留升级后新增的同名自定义介质。
DO $$
DECLARE
    builtin_id BIGINT;
    chosen_name TEXT := '米糠油';
    suffix INT := 0;
BEGIN
    LOCK TABLE media IN EXCLUSIVE MODE;
    SELECT id INTO builtin_id FROM media WHERE builtin_index = 10;
    IF builtin_id IS NULL THEN
        RETURN;
    END IF;

    WHILE EXISTS (
        SELECT 1 FROM media
        WHERE scope = 'public' AND id <> builtin_id
          AND lower(btrim(name)) = lower(btrim(chosen_name))
    ) LOOP
        suffix := suffix + 1;
        chosen_name := CASE WHEN suffix = 1 THEN '米糠油（内置）'
                            ELSE format('米糠油（内置%s）', suffix) END;
    END LOOP;

    UPDATE media
    SET name = chosen_name, name_en = 'Rice Bran Oil', updated_at = now()
    WHERE id = builtin_id;
END $$;
