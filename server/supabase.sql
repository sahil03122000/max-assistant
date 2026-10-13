-- Max ki memory ka table. Supabase dashboard → SQL Editor → New query → ye paste karke "Run".

create table if not exists memories (
  id          text primary key,
  user_id     text not null,
  text        text not null,
  category    text not null default 'other',
  created_at  timestamptz not null default now()
);

create index if not exists memories_user_created on memories (user_id, created_at);

-- Server "service key" se baat karta hai jo in rules ko bypass karti hai. Koi policy nahi = bahar se
-- (public "anon" key se) koi bhi is table ko padh/likh nahi sakta.
alter table memories enable row level security;
