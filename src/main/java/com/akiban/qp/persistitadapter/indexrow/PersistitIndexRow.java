/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.qp.persistitadapter.indexrow;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.IndexToHKey;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.persistitadapter.PersistitHKey;
import com.akiban.qp.row.HKey;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.util.HKeyCache;
import com.akiban.server.PersistitKeyPValueSource;
import com.akiban.server.PersistitKeyValueSource;
import com.akiban.server.collation.AkCollator;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.ValueTarget;
import com.akiban.server.types.conversion.Converters;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.Types3Switch;
import com.akiban.server.types3.pvalue.PUnderlying;
import com.akiban.server.types3.pvalue.PValueSource;
import com.akiban.server.types3.mcompat.mtypes.MNumeric;
import com.akiban.util.AkibanAppender;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.exception.PersistitException;

public abstract class PersistitIndexRow extends PersistitIndexRowBuffer
{
    // Object interface

    @Override
    public final String toString()
    {
        ValueTarget buffer = AkibanAppender.of(new StringBuilder()).asValueTarget();
        buffer.putString("(");
        for (int i = 0; i < nIndexFields; i++) {
            if (i > 0) {
                buffer.putString(", ");
            }
            Converters.convert(eval(i), buffer);
        }
        buffer.putString(")->");
        buffer.putString(hKey().toString());
        return buffer.toString();
    }
    
    // RowBase interface

    // TODO: This is not a correct implementation of hKey, because it returns an empty hKey to be filled in
    // TODO: by the caller. Normally, hKey returns the HKey of the row.
    @Override
    public final HKey hKey()
    {
        return hKeyCache.hKey(leafmostTable);
    }

    @Override
    public final RowType rowType()
    {
        return indexRowType;
    }

    @Override
    public final ValueSource eval(int i)
    {
        PersistitKeyValueSource keySource = keySource(i);
        attach(keySource, i, akTypes[i], akCollators[i]);
        return keySource;
    }

    @Override
    public final PValueSource pvalue(int i)
    {
        TInstance tInstance = tInstances[i];
        PersistitKeyPValueSource keySource = keyPSource(i, tInstance);
        attach(keySource, i, tInstance);
        return keySource;
    }

    // PersistitIndexRow interface

    public abstract IndexToHKey indexToHKey();

    public long tableBitmap()
    {
        throw new UnsupportedOperationException(getClass().toString());
    }

    public void copyFromExchange(Exchange exchange) throws PersistitException
    {
        copyFrom(exchange);
        constructHKeyFromIndexKey(hKeyCache.hKey(leafmostTable).key(), indexToHKey());
    }

    public void reset()
    {
        keyState.clear();
        super.reset();
    }

    // For use by subclasses

    protected PersistitIndexRow(PersistitAdapter adapter, IndexRowType indexRowType)
    {
        super(adapter);
        this.keyState = adapter.persistit().getKey();
        resetForWrite(indexRowType.index(), keyState);
        this.indexRowType = indexRowType;
        this.leafmostTable = (UserTable) index.leafMostTable();
        this.hKeyCache = new HKeyCache<PersistitHKey>(adapter);
        if (Types3Switch.ON) {
            this.tInstances = index.tInstances();
            this.akTypes = null;
            this.akCollators = null;
        }
        else {
            this.akTypes = index.akTypes();
            this.akCollators = index.akCollators();
            this.tInstances = null;
        }
    }

    // For use by this class

    private PersistitKeyValueSource keySource(int i)
    {
        if (keySources == null)
            keySources = new PersistitKeyValueSource[nIndexFields];
        if (keySources[i] == null) {
            keySources[i] = new PersistitKeyValueSource();
        }
        return keySources[i];
    }

    private PersistitKeyPValueSource keyPSource(int i, TInstance tInstance)
    {
        if (keyPSources == null)
            keyPSources = new PersistitKeyPValueSource[nIndexFields];
        if (keyPSources[i] == null) {
            keyPSources[i] = new PersistitKeyPValueSource(tInstance);
        }
        return keyPSources[i];
    }

    // Object state

    protected final HKeyCache<PersistitHKey> hKeyCache;
    protected final UserTable leafmostTable;
    private final Key keyState;
    private final IndexRowType indexRowType;
    private final AkType[] akTypes;
    private final AkCollator[] akCollators;
    private final TInstance[] tInstances;
    private PersistitKeyValueSource[] keySources;
    private PersistitKeyPValueSource[] keyPSources;
}
