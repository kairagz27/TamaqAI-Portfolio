CREATE TABLE user_fitness (
 user_id uuid PRIMARY KEY REFERENCES users(id),
 version integer NOT NULL DEFAULT 1 CHECK(version > 0),
 document jsonb NOT NULL,
 updated_at bigint NOT NULL
);
CREATE TABLE user_goals (
 user_id uuid NOT NULL REFERENCES users(id),
 effective_date date NOT NULL,
 document jsonb NOT NULL,
 updated_at bigint NOT NULL,
 PRIMARY KEY(user_id, effective_date)
);
CREATE TABLE meal_plans (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id),
 version integer NOT NULL CHECK(version > 0),
 document jsonb NOT NULL,
 updated_at bigint NOT NULL
);
CREATE INDEX meal_plans_owner ON meal_plans(user_id, updated_at DESC);
CREATE TABLE plan_mutations (
 user_id uuid NOT NULL REFERENCES users(id),
 operation uuid NOT NULL,
 digest text NOT NULL,
 response jsonb NOT NULL,
 PRIMARY KEY(user_id, operation)
);
