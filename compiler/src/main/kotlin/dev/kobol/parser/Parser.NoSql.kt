package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

    //  Group 13 — NoSQL document store (MongoDB)
    // =========================================================================

    /**
     * NOSQL CONNECT TO url DATABASE db-name
     * NOSQL DISCONNECT
     */
internal fun Parser.parseNoSqlStatement(): Statement {
        val pos = currentPos()
        advance() // consume NOSQL
        val kw = peek().value.uppercase()
        return when (kw) {
            "CONNECT" -> {
                advance() // CONNECT
                expect(TO, "Expected TO after NOSQL CONNECT")
                val url = parseExpression()
                expect(DATABASE, "Expected DATABASE after url in NOSQL CONNECT")
                val database = parseExpression()
                NoSqlConnectStatement(url, database, pos)
            }
            "DISCONNECT" -> {
                advance() // DISCONNECT
                NoSqlDisconnectStatement(pos)
            }
            else -> throw error("Expected CONNECT or DISCONNECT after NOSQL (got '$kw')")
        }
    }

    /**
     * FIND [ONE] IN collection [WHERE expr] GIVING var
     */
internal fun Parser.parseNoSqlFindStatement(): Statement {
        val pos = currentPos()
        advance() // consume FIND
        val findOne = if (peek().value.uppercase() == "ONE") { advance(); true } else false
        expect(IN, "Expected IN after FIND [ONE]")
        val collection = parseExpression()
        val filter = if (check(WHERE)) { advance(); parseExpression() } else null
        expectKeyword("GIVING", "Expected GIVING after collection in FIND")
        val giving = parseReference()
        return NoSqlFindStatement(collection, filter, findOne, giving, pos)
    }

    /**
     * SAVE TO collection USING document-var [UPSERT]
     */
internal fun Parser.parseNoSqlSaveStatement(): Statement {
        val pos = currentPos()
        advance() // consume SAVE
        expect(TO, "Expected TO after SAVE")
        val collection = parseExpression()
        expect(USING, "Expected USING after collection in SAVE")
        val document = parseReference()
        val upsert = if (peek().value.uppercase() == "UPSERT") { advance(); true } else false
        return NoSqlSaveStatement(collection, document, upsert, pos)
    }

    /**
     * DELETE FROM collection WHERE expr
     */
internal fun Parser.parseNoSqlDeleteStatement(): Statement {
        val pos = currentPos()
        advance() // consume DELETE
        expect(FROM, "Expected FROM after DELETE")
        val collection = parseExpression()
        expect(WHERE, "Expected WHERE after collection in DELETE")
        val filter = parseExpression()
        return NoSqlDeleteStatement(collection, filter, pos)
    }

    /**
     * COUNT IN collection [WHERE expr] GIVING var
     */
internal fun Parser.parseNoSqlCountStatement(): Statement {
        val pos = currentPos()
        advance() // consume COUNT
        expect(IN, "Expected IN after COUNT")
        val collection = parseExpression()
        val filter = if (check(WHERE)) { advance(); parseExpression() } else null
        expectKeyword("GIVING", "Expected GIVING after collection in COUNT")
        val giving = parseReference()
        return NoSqlCountStatement(collection, filter, giving, pos)
    }

    // =========================================================================
    //  Group 14 — Cache / key-value store (Redis)
    // =========================================================================

    /**
     * CACHE CONNECT TO url
     * CACHE DISCONNECT
     * CACHE GET key GIVING value
     * CACHE SET key TO value [EXPIRES IN n SECONDS]
     * CACHE DELETE key
     * CACHE EXISTS key GIVING found
     */
internal fun Parser.parseCacheStatement(): Statement {
        val pos = currentPos()
        advance() // consume CACHE
        val kw = peek().value.uppercase()
        return when (kw) {
            "CONNECT" -> {
                advance() // CONNECT
                expect(TO, "Expected TO after CACHE CONNECT")
                val url = parseExpression()
                CacheConnectStatement(url, pos)
            }
            "DISCONNECT" -> {
                advance() // DISCONNECT
                CacheDisconnectStatement(pos)
            }
            "GET" -> {
                advance() // GET
                val key = parseExpression()
                expectKeyword("GIVING", "Expected GIVING after key in CACHE GET")
                val giving = parseReference()
                CacheGetStatement(key, giving, pos)
            }
            "SET" -> {
                advance() // SET
                val key = parseExpression()
                expect(TO, "Expected TO after key in CACHE SET")
                val value = parseExpression()
                val ttl = if (check(EXPIRES)) {
                    advance() // EXPIRES
                    expect(IN, "Expected IN after EXPIRES")
                    val n = parseExpression()
                    expect(SECONDS, "Expected SECONDS after TTL value")
                    n
                } else null
                CacheSetStatement(key, value, ttl, pos)
            }
            "DELETE" -> {
                advance() // DELETE
                val key = parseExpression()
                CacheDeleteStatement(key, pos)
            }
            "EXISTS" -> {
                advance() // EXISTS
                val key = parseExpression()
                expectKeyword("GIVING", "Expected GIVING after key in CACHE EXISTS")
                val giving = parseReference()
                CacheExistsStatement(key, giving, pos)
            }
            else -> throw error("Unknown CACHE sub-command '$kw'")
        }
    }
