package sh.repost.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.DefaultSpec;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;

public final class DescriptorContractTest {
    @Test
    void snapshotsDeclarationOrderedDescriptorData() {
        FieldDescriptor title = FieldDescriptor.builder(0, "title", "book_title", ScalarKind.STRING)
                .requiredInInput(true)
                .build();
        FieldDescriptor subtitle = FieldDescriptor.builder(1, "subtitle", "subtitle", ScalarKind.STRING)
                .nullableInInput(true)
                .defaultSpec(DefaultSpec.literal("untitled"))
                .build();
        ModelDescriptor book = ModelDescriptor.of("Book", Arrays.asList(title, subtitle));
        LinkedHashMap<String, String> currency = new LinkedHashMap<>();
        currency.put("USD", "usd");
        currency.put("EUR", "eur");
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addEnum("Currency", currency)
                .addModel(book)
                .addEvent("book", "created", EventDescriptor.of("book.created", "Book"))
                .build();
        currency.clear();

        assertEquals(2, schema.getDescriptorFormatVersion());
        assertEquals(Arrays.asList("USD", "EUR"),
                new java.util.ArrayList<>(schema.getEnums().get("Currency").keySet()));
        assertEquals("book_title", schema.getModels().get("Book").getFields().get(0).getWireName());
        assertEquals("book.created", schema.getWebhooks().get("book").get("created").getType());
        expectThrows(UnsupportedOperationException.class,
                () -> schema.getModels().clear());
    }

    @Test
    void rejectsDuplicateAndBrokenDescriptorReferences() {
        FieldDescriptor first = FieldDescriptor.builder(0, "first", "same", ScalarKind.STRING)
                .build();
        FieldDescriptor second = FieldDescriptor.builder(1, "second", "same", ScalarKind.STRING)
                .build();
        expectThrows(IllegalArgumentException.class,
                () -> ModelDescriptor.of("Broken", Arrays.asList(first, second)));

        FieldDescriptor missingModel = FieldDescriptor.builder(
                        0, "author", "author", ScalarKind.MODEL)
                .descriptorId("Missing")
                .build();
        ModelDescriptor book = ModelDescriptor.of("Book", java.util.Collections.singletonList(missingModel));
        expectThrows(IllegalArgumentException.class,
                () -> SchemaDescriptor.builder(2).addModel(book).build());

        expectThrows(IllegalArgumentException.class, () -> SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Book", java.util.Collections.emptyList()))
                .addEvent("book", "created", EventDescriptor.of("book.created", "Book"))
                .addEvent("movie", "created", EventDescriptor.of("book.created", "Book"))
                .build());
    }

    @Test
    void rejectsEveryDuplicateIdentityAndWrongKindReference() {
        FieldDescriptor first = FieldDescriptor.builder(
                0, "first", "first", ScalarKind.STRING).build();
        FieldDescriptor duplicateIndex = FieldDescriptor.builder(
                0, "second", "second", ScalarKind.STRING).build();
        expectThrows(IllegalArgumentException.class,
                () -> ModelDescriptor.of("DuplicateIndex", Arrays.asList(first, duplicateIndex)));

        ModelDescriptor payload = ModelDescriptor.of("Payload", Collections.emptyList());
        expectThrows(IllegalArgumentException.class, () -> SchemaDescriptor.builder(2)
                .addModel(payload)
                .addModel(payload));

        LinkedHashMap<String, String> members = new LinkedHashMap<>();
        members.put("FIRST", "same");
        members.put("SECOND", "same");
        expectThrows(IllegalArgumentException.class,
                () -> SchemaDescriptor.builder(2).addEnum("DuplicateWire", members));

        FieldDescriptor wrongKind = FieldDescriptor.builder(
                        0, "status", "status", ScalarKind.ENUM)
                .descriptorId("Status")
                .build();
        ModelDescriptor wrong = ModelDescriptor.of(
                "Wrong", Collections.singletonList(wrongKind));
        expectThrows(IllegalArgumentException.class, () -> SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Status", Collections.emptyList()))
                .addModel(wrong)
                .build());
    }

    @Test
    void rejectsMalformedDescriptorTextAndLiteralSnapshots() {
        expectThrows(IllegalArgumentException.class,
                () -> FieldDescriptor.builder(0, "not.dot", "wire", ScalarKind.STRING).build());
        expectThrows(IllegalArgumentException.class,
                () -> ModelDescriptor.of("\ud800", Collections.emptyList()));
        expectThrows(IllegalArgumentException.class,
                () -> EventDescriptor.of("event.\ud800", "Payload"));
        expectThrows(IllegalArgumentException.class,
                () -> DefaultSpec.literal(Double.NaN));
        expectThrows(IllegalArgumentException.class,
                () -> DefaultSpec.literal("\ud800"));

        java.util.ArrayList<Object> mutable = new java.util.ArrayList<>();
        mutable.add("before");
        DefaultSpec literal = DefaultSpec.literal(mutable);
        mutable.clear();
        assertEquals(Collections.singletonList("before"), literal.getLiteralValue());
    }

    @Test
    void generatedClientRegistryIsExplicitAndImmutable() {
        GeneratedRepostClientFactory<String> factory = new GeneratedRepostClientFactory<String>() {
            @Override
            public Class<String> clientType() {
                return String.class;
            }

            @Override
            public String create(RepostRuntime runtime) {
                return "client";
            }
        };
        java.util.List<GeneratedRepostClientFactory<?>> factories =
                java.util.Collections.<GeneratedRepostClientFactory<?>>singletonList(factory);
        GeneratedRepostClientRegistry registry = () -> factories;
        assertEquals(factory, registry.factories().get(0));
        expectThrows(UnsupportedOperationException.class, () -> registry.factories().clear());
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError("expected " + type.getName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName());
    }
}
