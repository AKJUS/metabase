import { useCallback } from "react";
import { t } from "ttag";
import _ from "underscore";

import type {
  MoveCollectionDestination,
  MoveDestination,
  OnMoveWithOneItem,
} from "metabase/collections/types";
import { isItemCollection } from "metabase/collections/utils";
import {
  type CollectionPickerItem,
  CollectionPickerModal,
  type CollectionPickerModel,
  type CollectionPickerValueItem,
} from "metabase/common/components/Pickers/CollectionPicker";
import type {
  CollectionId,
  CollectionItem,
  RecentItem,
  SearchResult,
} from "metabase-types/api";

interface BaseMoveModalProps {
  title: string;
  onClose: () => void;
  initialCollectionId: CollectionId;
  movingCollectionId?: CollectionId;
  recentAndSearchFilter?: (item: CollectionPickerItem) => boolean;
}

type MoveModalProps =
  | (BaseMoveModalProps & {
      canMoveToDashboard?: true | undefined;
      onMove: OnMoveWithOneItem;
    })
  | (BaseMoveModalProps & {
      canMoveToDashboard?: false;
      onMove: OnMoveWithOneItem<MoveCollectionDestination>;
    });

const makeRecentFilter = (
  disableFns: (((item: CollectionPickerItem) => boolean) | undefined)[],
) => {
  return (recentItems: RecentItem[]) =>
    recentItems.filter((result) =>
      disableFns
        .map((disableFn) => !disableFn?.(result as CollectionPickerItem))
        .every((val) => val === true),
    );
};

const makeSearchResultFilter = (
  disableFns: (((item: CollectionPickerItem) => boolean) | undefined)[],
) => {
  return (searchResults: SearchResult[]) =>
    searchResults.filter((result) =>
      disableFns
        .map((disableFn) => !disableFn?.(result as CollectionPickerItem))
        .every((val) => val === true),
    );
};

export const MoveModal = ({
  title,
  onClose,
  onMove,
  initialCollectionId,
  movingCollectionId,
  canMoveToDashboard,
  recentAndSearchFilter,
}: MoveModalProps) => {
  // if we are moving a collection, we can't move it into itself or any of its children
  const shouldDisableItem = movingCollectionId
    ? (item: CollectionPickerItem) =>
        Boolean(
          item.id === movingCollectionId ||
            (item.effective_location ?? item?.location)
              ?.split("/")
              .includes(String(movingCollectionId)),
        )
    : undefined;

  const searchResultFilter = makeSearchResultFilter([
    shouldDisableItem,
    recentAndSearchFilter,
  ]);

  const recentFilter = makeRecentFilter([
    (item) => {
      return Boolean(!item.can_write || shouldDisableItem?.(item));
    },
    recentAndSearchFilter,
  ]);

  const handleMove = useCallback(
    (destination: CollectionPickerValueItem) => {
      // GROSS:
      // - CollectionPicker's `onChange` prop isn't generic to its `models` prop, so
      //   `onChange`'s destination arg isn't narrowed based on the `models` passed in. This
      //   requires we do additional type gauarding / unneeded error handling below.
      // - To keep this same issue from bubbling up to consumers of MoveModal, we need
      //   do some extra type casting so it has an external API where `canMoveToDashboard`
      //   narrows the `destination` arg for its `onMove` prop.
      // - Making CollectionPicker properly generic is hard due to some internal typing
      //   being used by components other than the CollectionPicker. One type
      //   cast here avoids a large headache-inducing refactor there.

      if (!canMoveToDashboard) {
        if (destination.model === "dashboard") {
          throw new Error(
            "MoveModal can't move to a dashboard with canMoveToDashboard=false",
          );
        }

        return onMove({ id: destination.id, model: destination.model });
      } else {
        return onMove({
          id: destination.id,
          model: destination.model,
        } as MoveDestination);
      }
    },
    [onMove, canMoveToDashboard],
  );

  const models: CollectionPickerModel[] = canMoveToDashboard
    ? ["collection", "dashboard"]
    : ["collection"];

  return (
    <CollectionPickerModal
      title={title}
      value={{
        id: initialCollectionId,
        model: "collection",
      }}
      onChange={handleMove}
      models={models}
      options={{
        showSearch: true,
        allowCreateNew: true,
        hasConfirmButtons: true,
        showRootCollection: true,
        showPersonalCollections: true,
        confirmButtonText: t`Move`,
      }}
      shouldDisableItem={shouldDisableItem}
      searchResultFilter={searchResultFilter}
      recentFilter={recentFilter}
      onClose={onClose}
    />
  );
};

interface BulkMoveModalProps {
  onClose: () => void;
  onMove: OnMoveWithOneItem<MoveDestination>;
  selectedItems: CollectionItem[];
  initialCollectionId: CollectionId;
  recentAndSearchFilter?: (item: CollectionPickerItem) => boolean;
}

export const BulkMoveModal = ({
  onClose,
  onMove,
  selectedItems,
  initialCollectionId,
  recentAndSearchFilter,
}: BulkMoveModalProps) => {
  const movingCollectionIds = selectedItems
    .filter((item: CollectionItem) => isItemCollection(item))
    .map((item: CollectionItem) => String(item.id));

  // if the move set includes collections, we can't move into any of them
  const shouldDisableItem = movingCollectionIds.length
    ? (item: CollectionPickerItem) => {
        const collectionItemFullPath =
          (item?.effective_location ?? item?.location)
            ?.split("/")
            .map(String)
            .concat(String(item.id)) ?? [];
        return (
          _.intersection(collectionItemFullPath, movingCollectionIds).length > 0
        );
      }
    : undefined;

  const searchResultFilter = makeSearchResultFilter([
    shouldDisableItem,
    recentAndSearchFilter,
  ]);
  const recentFilter = makeRecentFilter([
    shouldDisableItem,
    recentAndSearchFilter,
  ]);

  const title =
    selectedItems.length > 1
      ? t`Move ${selectedItems.length} items?`
      : t`Move "${selectedItems[0].name}"?`;

  const canMoveToDashboard = selectedItems.every(
    (item) => item.model === "card",
  );

  const models: CollectionPickerModel[] = canMoveToDashboard
    ? ["collection", "dashboard"]
    : ["collection"];

  return (
    <CollectionPickerModal
      title={title}
      value={{
        id: initialCollectionId,
        model: "collection",
      }}
      onChange={(destination) => {
        onMove({
          id: destination.id,
          model: destination.model,
        } as MoveDestination);
      }}
      options={{
        showSearch: true,
        allowCreateNew: true,
        hasConfirmButtons: true,
        showRootCollection: true,
        showPersonalCollections: true,
        confirmButtonText: t`Move`,
      }}
      shouldDisableItem={shouldDisableItem}
      searchResultFilter={searchResultFilter}
      recentFilter={recentFilter}
      onClose={onClose}
      models={models}
    />
  );
};
