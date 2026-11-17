-- 只改内置条目；同名自定义介质保留原名、ID 及位置映射。
DO $$
DECLARE
    builtin_id BIGINT;
    chosen_name TEXT := '煤油';
    suffix INT := 0;
BEGIN
    -- 检查名称和写入期间阻止并发新增、改名。
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
        chosen_name := CASE WHEN suffix = 1 THEN '煤油（内置）'
                            ELSE format('煤油（内置%s）', suffix) END;
    END LOOP;

    UPDATE media
    SET name = chosen_name, name_en = 'Kerosene', updated_at = now()
    WHERE id = builtin_id;
END $$;
