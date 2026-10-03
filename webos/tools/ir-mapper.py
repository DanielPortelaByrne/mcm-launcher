"""MCM Home adapter for the LG 50UA73006LA's LGE RCU input device.

Uses the pinned Magic Mapper runtime; changes only its input device selection.
All buttons other than Home pass through unchanged. SIGTERM releases the grab.
"""
import managed_mapper
managed_mapper.upstream.DEVICE_NAME = 'LGE RCU'
if __name__ == '__main__':
    managed_mapper.main()
