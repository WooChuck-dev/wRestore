#include "sqlite.h"
#include "sqlite3.h"
#include <stdlib.h>
#include <string.h>

#define MAX_DB 16
static sqlite3 *dbs[MAX_DB];

static bool fail(sqlite_string_t *err, const char *msg) {
    sqlite_string_dup(err, msg ? msg : "unknown error");
    return false;
}

static char *dupn(sqlite_string_t *s) {
    char *p = malloc(s->len + 1);
    memcpy(p, s->ptr, s->len);
    p[s->len] = 0;
    return p;
}

bool exports_wrestore_sqlite_db_open(sqlite_string_t *path, uint32_t *ret, sqlite_string_t *err) {
    char *p = dupn(path);
    for (uint32_t i = 0; i < MAX_DB; i++) {
        if (dbs[i]) continue;
        int rc = sqlite3_open(p, &dbs[i]);
        free(p);
        if (rc != SQLITE_OK) {
            bool r = fail(err, dbs[i] ? sqlite3_errmsg(dbs[i]) : "open failed");
            if (dbs[i]) { sqlite3_close(dbs[i]); dbs[i] = 0; }
            return r;
        }
        *ret = i;
        return true;
    }
    free(p);
    return fail(err, "too many open databases");
}

bool exports_wrestore_sqlite_db_exec(uint32_t h, sqlite_string_t *sql, sqlite_string_t *err) {
    if (h >= MAX_DB || !dbs[h]) return fail(err, "bad handle");
    char *q = dupn(sql), *msg = 0;
    int rc = sqlite3_exec(dbs[h], q, 0, 0, &msg);
    free(q);
    if (rc != SQLITE_OK) { bool r = fail(err, msg); sqlite3_free(msg); return r; }
    return true;
}

typedef struct { char *b; size_t n, cap; } buf_t;
static void put(buf_t *b, const char *s, size_t n) {
    if (b->n + n + 1 > b->cap) { b->cap = (b->n + n + 1) * 2; b->b = realloc(b->b, b->cap); }
    memcpy(b->b + b->n, s, n);
    b->n += n;
}

bool exports_wrestore_sqlite_db_query(uint32_t h, sqlite_string_t *sql, sqlite_string_t *ret, sqlite_string_t *err) {
    if (h >= MAX_DB || !dbs[h]) return fail(err, "bad handle");
    char *q = dupn(sql);
    sqlite3_stmt *st = 0;
    int rc = sqlite3_prepare_v2(dbs[h], q, -1, &st, 0);
    free(q);
    if (rc != SQLITE_OK) return fail(err, sqlite3_errmsg(dbs[h]));
    buf_t b = {0};
    int ncol = sqlite3_column_count(st);
    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        for (int c = 0; c < ncol; c++) {
            if (c) put(&b, "\t", 1);
            const unsigned char *t = sqlite3_column_text(st, c);
            if (!t) { put(&b, "\\N", 2); continue; }
            for (; *t; t++) {
                if (*t == '\\') put(&b, "\\\\", 2);
                else if (*t == '\t') put(&b, "\\t", 2);
                else if (*t == '\n') put(&b, "\\n", 2);
                else put(&b, (const char *)t, 1);
            }
        }
        put(&b, "\n", 1);
    }
    bool ok = rc == SQLITE_DONE;
    if (!ok) fail(err, sqlite3_errmsg(dbs[h]));
    sqlite3_finalize(st);
    if (ok) sqlite_string_dup_n(ret, b.b ? b.b : "", b.n);
    free(b.b);
    return ok;
}

void exports_wrestore_sqlite_db_close(uint32_t h) {
    if (h < MAX_DB && dbs[h]) { sqlite3_close(dbs[h]); dbs[h] = 0; }
}
