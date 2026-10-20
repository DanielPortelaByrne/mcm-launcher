package com.example.tvlauncher.data.movies

import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    @Test fun finds_a_movie_by_its_tmdb_id() {
        val list = """[{"id":3,"tmdbId":438631},{"id":9,"tmdbId":1402}]"""
        assertEquals(9, LibraryApi.radarrMovieId(list, 1402L))
        assertNull(LibraryApi.radarrMovieId(list, 7L))
        assertNull(LibraryApi.radarrMovieId("junk", 1402L))
    }

    @Test fun deleting_removes_files_and_the_entry_so_nothing_is_fetched_again() {
        assertEquals("/api/v3/series/12?deleteFiles=true&addImportListExclusion=false", LibraryApi.deletePath(Kind.SHOW, 12))
        assertEquals("/api/v3/movie/5?deleteFiles=true&addImportExclusion=false", LibraryApi.deletePath(Kind.MOVIE, 5))
    }
}
