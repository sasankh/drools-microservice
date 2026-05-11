package com.company.drools.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageFactory")
class StorageFactoryTest {

  @Mock private ApplicationContext applicationContext;
  @Mock private InMemoryRuleStorageAdapter inMemoryAdapter;
  @Mock private LocalFileStorage localFileStorage;
  @Mock private S3RuleStorage s3RuleStorage;

  private StorageFactory storageFactory;

  @BeforeEach
  void setUp() {
    storageFactory = new StorageFactory(applicationContext);
  }

  @Test
  @DisplayName("creates in-memory storage for 'local' source")
  void testLocalSource() throws Exception {
    setField(storageFactory, "ruleSource", "local");
    when(applicationContext.getBean(InMemoryRuleStorageAdapter.class)).thenReturn(inMemoryAdapter);

    RuleStorage storage = storageFactory.createRuleStorage();
    assertThat(storage).isEqualTo(inMemoryAdapter);
  }

  @Test
  @DisplayName("creates file storage for 'file' source")
  void testFileSource() throws Exception {
    setField(storageFactory, "ruleSource", "file");
    when(applicationContext.getBean("localFileStorage", RuleStorage.class))
        .thenReturn(localFileStorage);

    RuleStorage storage = storageFactory.createRuleStorage();
    assertThat(storage).isEqualTo(localFileStorage);
  }

  @Test
  @DisplayName("creates S3 storage for 's3' source")
  void testS3Source() throws Exception {
    setField(storageFactory, "ruleSource", "s3");
    when(applicationContext.getBean("s3RuleStorage", RuleStorage.class)).thenReturn(s3RuleStorage);

    RuleStorage storage = storageFactory.createRuleStorage();
    assertThat(storage).isEqualTo(s3RuleStorage);
  }

  @Test
  @DisplayName("defaults to in-memory for unknown source")
  void testUnknownSource() throws Exception {
    setField(storageFactory, "ruleSource", "unknown");
    when(applicationContext.getBean(InMemoryRuleStorageAdapter.class)).thenReturn(inMemoryAdapter);

    RuleStorage storage = storageFactory.createRuleStorage();
    assertThat(storage).isEqualTo(inMemoryAdapter);
  }

  @Test
  @DisplayName("handles uppercase source names")
  void testUppercaseSource() throws Exception {
    setField(storageFactory, "ruleSource", "S3");
    when(applicationContext.getBean("s3RuleStorage", RuleStorage.class)).thenReturn(s3RuleStorage);

    RuleStorage storage = storageFactory.createRuleStorage();
    assertThat(storage).isEqualTo(s3RuleStorage);
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
